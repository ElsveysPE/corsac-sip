package test.unit.gov.nist.javax.sip.stack.contentlength;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sip.header.CSeqHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ToHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

import gov.nist.core.CommonLogger;
import gov.nist.core.StackLogger;
import gov.nist.javax.sip.message.MessageFactoryImpl;
import test.tck.msgflow.callflows.TestAssertion;

/**
 * This class is a UAC template. Shootist is the guy that shoots and shootme is
 * the guy that gets shot.
 */
public class Shootist {

    private static final StackLogger logger = CommonLogger.getLogger(Shootist.class);
    private static final String myAddress = "127.0.0.1";

    static final String BODY = "hello world";

    static final String CALL_ID = "content-length-1";

    static final String WELL_FRAMED_CALL_ID = "content-length-2";

    private final int port;
    private final int peerPort;
    private final DatagramSocket socket;
    private final MessageFactoryImpl messageFactory = new MessageFactoryImpl();

    /** as received */
    private final List<String> receivedResponses = Collections.synchronizedList(new ArrayList<String>());

    public Shootist(int port, int peerPort) {
        this.port = port;
        this.peerPort = peerPort;
        try {
            this.socket = new DatagramSocket(port, InetAddress.getByName(myAddress));
        } catch (Exception ex) {
            throw new RuntimeException("could not create shootist", ex);
        }
        Thread receiver = new Thread(new Runnable() {
            @Override
            public void run() {
                receive();
            }
        }, "shootist-" + port);
        receiver.setDaemon(true);
        receiver.start();
    }

    /** MESSAGE with BODY, headers go right before the empty line, each with its CRLF, "" for none */
    public void sendMessage(String headers) throws IOException {
        logger.logInfo("shootist:" + port + " sending MESSAGE with " + headers);
        send(request(Request.MESSAGE, CALL_ID, "Content-Type: text/plain\r\n" + headers, BODY));
    }

    /** OPTIONS without body, headers as in sendMessage */
    public void sendOptions(String headers) throws IOException {
        logger.logInfo("shootist:" + port + " sending OPTIONS with " + headers);
        send(request(Request.OPTIONS, CALL_ID, headers, ""));
    }

    /** INVITE without body, the 200 gets an ACK without Content-Length */
    public void sendInvite() throws IOException {
        logger.logInfo("shootist:" + port + " sending INVITE");
        send(request(Request.INVITE, CALL_ID,
                "Contact: <sip:alice@" + myAddress + ":" + port + ">\r\n" + "Content-Length: 0\r\n", ""));
    }

    /** OPTIONS with Content-Length: 0, Call-ID WELL_FRAMED_CALL_ID */
    public void sendWellFramedOptions() throws IOException {
        send(request(Request.OPTIONS, WELL_FRAMED_CALL_ID, "Content-Length: 0\r\n", ""));
    }

    private String request(String method, String callId, String headers, String body) {
        return method + " sip:bob@" + myAddress + ":" + peerPort + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP " + myAddress + ":" + port + ";branch=z9hG4bK-" + callId + "\r\n"
                + "Max-Forwards: 70\r\n"
                + "From: <sip:alice@" + myAddress + ">;tag=" + port + "\r\n"
                + "To: <sip:bob@" + myAddress + ">\r\n"
                + "Call-ID: " + callId + "\r\n"
                + "CSeq: 1 " + method + "\r\n"
                + headers
                + "\r\n"
                + body;
    }

    private void sendAck(Response response) throws IOException {
        String toTag = ((ToHeader) response.getHeader(ToHeader.NAME)).getTag();
        String contact = ((ContactHeader) response.getHeader(ContactHeader.NAME)).getAddress().getURI().toString();
        String ack = "ACK " + contact + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP " + myAddress + ":" + port + ";branch=z9hG4bK-" + CALL_ID + "-ack\r\n"
                + "Max-Forwards: 70\r\n"
                + "From: <sip:alice@" + myAddress + ">;tag=" + port + "\r\n"
                + "To: <sip:bob@" + myAddress + ">;tag=" + toTag + "\r\n"
                + "Call-ID: " + CALL_ID + "\r\n"
                + "CSeq: 1 ACK\r\n"
                + "\r\n";
        logger.logInfo("shootist:" + port + " sending ACK without Content-Length");
        send(ack);
    }

    private void send(String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(myAddress), peerPort));
    }

    private void receive() {
        byte[] buffer = new byte[65535];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException ex) {
                // socket closed by stop()
                return;
            }
            handle(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
        }
    }

    private void handle(String message) {
        receivedResponses.add(message);
        try {
            Response response = messageFactory.createResponse(message);
            String method = ((CSeqHeader) response.getHeader(CSeqHeader.NAME)).getMethod();
            logger.logInfo("shootist:" + port + " received " + response.getStatusCode() + " to " + method);
            if (response.getStatusCode() == Response.OK && method.equals(Request.INVITE)) {
                sendAck(response);
            }
        } catch (Exception ex) {
            logger.logError("shootist: could not handle what came back:\n" + message, ex);
        }
    }

    public String getResponse(int statusCode) {
        synchronized (receivedResponses) {
            for (String response : receivedResponses) {
                if (response.startsWith("SIP/2.0 " + statusCode + " ") && response.contains(CALL_ID)) {
                    return response;
                }
            }
        }
        return null;
    }

    public TestAssertion getResponseAssertion(final int statusCode) {
        return new TestAssertion() {
            @Override
            public boolean assertCondition() {
                return getResponse(statusCode) != null;
            }
        };
    }

    public void stop() {
        socket.close();
    }
}
