package slowscript.warpinator;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.google.protobuf.ByteString;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import javax.net.ssl.SSLException;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;

public class Remote {
    public enum RemoteStatus {
        CONNECTED,
        DISCONNECTED,
        CONNECTING, //Connect thread running -> Don't touch data!!
        ERROR, //Failed to connect
        AWAITING_DUPLEX
    }

    static String TAG = "Remote";

    public InetAddress address;
    public int port;
    public String serviceName; //Zeroconf service name, also uuid
    public String userName;
    public String hostname;
    public String displayName;
    public String uuid;
    public Bitmap picture;
    public RemoteStatus status;

    ArrayList<Transfer> transfers = new ArrayList<>();

    ManagedChannel channel;
    WarpGrpc.WarpBlockingStub blockingStub;
    WarpGrpc.WarpStub asyncStub;

    public void connect() {
        Log.i(TAG, "Connecting to " + hostname);
        status = RemoteStatus.CONNECTING;
        MainActivity.updateRemoteList();
        new Thread(() -> {
            //Receive certificate
            if (!receiveCertificate()) {
                status = RemoteStatus.ERROR;
                return;
            }
            Log.d(TAG, "Certificate for " + hostname + " received and saved");

            //Authenticate
            try {
                channel = OkHttpChannelBuilder.forAddress(address.getHostAddress(), port)
                        .sslSocketFactory(Authenticator.createSSLSocketFactory(uuid))
                        .build();
                blockingStub = WarpGrpc.newBlockingStub(channel);
                asyncStub = WarpGrpc.newStub(channel);
            } catch (SSLException e) {
                Log.e(TAG, "Authentication with remote "+ hostname +" failed: " + e.getMessage(), e);
                status = RemoteStatus.ERROR;
                return;
            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to remote " + hostname + ". " + e.getMessage(), e);
                status = RemoteStatus.ERROR;
                return;
            }

            status = RemoteStatus.AWAITING_DUPLEX;
            MainActivity.updateRemoteList();

            //Get duplex
            if (!waitForDuplex()) {
                Log.e(TAG, "Couldn't establish duplex with " + hostname);
                status = RemoteStatus.ERROR;
                return;
            }

            //Connection ready
            status = RemoteStatus.CONNECTED;

            //Get name
            WarpProto.RemoteMachineInfo info = blockingStub.getRemoteMachineInfo(WarpProto.LookupName.getDefaultInstance());
            displayName = info.getDisplayName();
            userName = info.getUserName();
            //Get avatar
            try {
                Iterator<WarpProto.RemoteMachineAvatar> avatar = blockingStub.getRemoteMachineAvatar(WarpProto.LookupName.getDefaultInstance());
                ByteString bs = avatar.next().getAvatarChunk();
                while (avatar.hasNext()) {
                    WarpProto.RemoteMachineAvatar a = avatar.next();
                    bs.concat(a.getAvatarChunk());
                }
                byte[] bytes = bs.toByteArray();
                picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception e) { //Profile picture not found, etc.
                picture = null;
            }

            MainActivity.updateRemoteList();
            Log.i(TAG, "Connection established with " + hostname);
        }).start();
    }

    public void disconnect() {
        Log.i(TAG, "Disconnecting " + hostname);
        channel.shutdownNow();
        status = RemoteStatus.DISCONNECTED;
    }

    public Transfer findTransfer(long timestamp) {
        for (Transfer t : transfers) {
            if(t.startTime == timestamp)
                return t;
        }
        return null;
    }

    public void startSendTransfer(Transfer t) {
        WarpProto.OpInfo info = WarpProto.OpInfo.newBuilder()
                .setIdent(Server.current.uuid)
                .setTimestamp(t.startTime)
                .setReadableName(Utils.getDeviceName())
                .build();
        WarpProto.TransferOpRequest op = WarpProto.TransferOpRequest.newBuilder()
                .setInfo(info)
                .setSenderName("Android")
                .setReceiver(uuid)
                .setSize(t.totalSize)
                .setCount(t.fileCount)
                .setNameIfSingle(t.singleName)
                .setMimeIfSingle(t.singleMime)
                .addAllTopDirBasenames(t.topDirBasenames)
                .build();
        asyncStub.processTransferOpRequest(op, null);
    }

    public void startReceiveTransfer(Transfer _t) {
        new Thread(() -> {
            Transfer t = _t; //_t gets garbage collected or something...
            WarpProto.OpInfo info = WarpProto.OpInfo.newBuilder()
                    .setIdent(Server.current.uuid)
                    .setTimestamp(t.startTime)
                    .setReadableName(Utils.getDeviceName()).build();
            try {
                Iterator<WarpProto.FileChunk> i = blockingStub.startTransfer(info);
                boolean cancelled = false;
                while (i.hasNext() && !cancelled) {
                    WarpProto.FileChunk c = i.next();
                    cancelled = !t.receiveFileChunk(c);
                }
                if (!cancelled)
                    t.finishReceive();
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.CANCELLED) {
                    Log.i(TAG, "Transfer cancelled", e);
                    t.status = Transfer.Status.STOPPED;
                } else {
                    Log.e(TAG, "Connection error", e);
                    t.status = Transfer.Status.FAILED;
                }
                t.updateUI();
            }
        }).start();
    }

    public void declineTransfer(Transfer t) {
        WarpProto.OpInfo info = WarpProto.OpInfo.newBuilder()
                .setIdent(Server.current.uuid)
                .setTimestamp(t.startTime)
                .setReadableName(Utils.getDeviceName())
                .build();
        asyncStub.cancelTransferOpRequest(info, null);
    }

    public void stopTransfer(Transfer t, boolean error) {
        WarpProto.OpInfo i = WarpProto.OpInfo.newBuilder()
                .setIdent(Server.current.uuid)
                .setTimestamp(t.startTime)
                .setReadableName(Utils.getDeviceName())
                .build();
        WarpProto.StopInfo info = WarpProto.StopInfo.newBuilder()
                .setError(error)
                .setInfo(i)
                .build();
        asyncStub.stopTransfer(info, null);
    }

    // -- PRIVATE HELPERS --

    private boolean receiveCertificate() {
        byte[] received = null;
        int tryCount = 0;
        while (tryCount < 3) {
            try {
                Log.v(TAG, "Receiving certificate from " + address.toString() + ", try " + tryCount);
                DatagramSocket sock = new DatagramSocket();
                sock.setSoTimeout(1000);

                byte[] req = CertServer.REQUEST.getBytes();
                DatagramPacket p = new DatagramPacket(req, req.length, address, port);
                sock.send(p);

                byte[] receiveData = new byte[2000];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                sock.receive(receivePacket);

                if (receivePacket.getAddress().equals(address) && (receivePacket.getPort() == port)) {
                    received = Arrays.copyOfRange(receivePacket.getData(), 0, receivePacket.getLength());
                    sock.close();
                    break;
                } //Received from wrong host. Give it another shot.
            } catch (Exception e) {
                tryCount++;
                Log.e(TAG, "What is this?", e);
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) { }
            }
        }
        if (tryCount == 3) {
            Log.e(TAG, "Failed to receive certificate from " + hostname);
            return false;
        }
        byte[] decoded = Base64.decode(received, Base64.DEFAULT);
        Authenticator.saveBoxedCert(decoded, uuid);
        return true;
    }

    private boolean waitForDuplex() {
        int tries = 0;
        while (tries < 10) {
            try {
                boolean haveDuplex = blockingStub.checkDuplexConnection(
                        WarpProto.LookupName.newBuilder()
                        .setId(Server.current.uuid).setReadableName("Android").build())
                        .getResponse();
                if (haveDuplex)
                    return true;
            } catch (Exception e) {
               Log.d(TAG, "Error while checking duplex", e);
            }
            Log.d (TAG, "Attempt " + tries + ": No duplex");
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) { throw new RuntimeException(e); }

            tries++;
        }
        return false;
    }
}