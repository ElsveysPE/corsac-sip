package test.unit.gov.nist.javax.sip.stack.contentlength;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.ListeningPoint;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.AddressFactory;
import javax.sip.header.CallIdHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ToHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

import gov.nist.core.CommonLogger;
import gov.nist.core.StackLogger;
import test.tck.msgflow.callflows.ProtocolObjects;
import test.tck.msgflow.callflows.TestAssertion;

/**
 * This class is a UAS template. Shootist is the guy that shoots and shootme is
 * the guy that gets shot.
 */
public class Shootme implements SipListener {

    private static final StackLogger logger = CommonLogger.getLogger(Shootme.class);
    private static final String myAddress = "127.0.0.1";

    /** what the listener got, copied out of the request */
    public static final class Received {
        public final String method;
        public final String callId;
        public final String body;

        Received(String method, String callId, String body) {
            this.method = method;
            this.callId = callId;
            this.body = body;
        }
    }

    private final int port;
    private final MessageFactory messageFactory;
    private final HeaderFactory headerFactory;
    private final AddressFactory addressFactory;
    private final SipStack sipStack;
    private SipProvider sipProvider;

    private final List<Received> receivedRequests = Collections.synchronizedList(new ArrayList<Received>());

    public Shootme(int port, String messageProcessorFactory) {
        this.port = port;
        Properties properties = new Properties();
        properties.setProperty("gov.nist.javax.sip.MESSAGE_PROCESSOR_FACTORY", messageProcessorFactory);
        ProtocolObjects protocolObjects = new ProtocolObjects("shootme-" + port, "gov.nist", "udp", true, false,
                false, properties);
        this.messageFactory = protocolObjects.messageFactory;
        this.headerFactory = protocolObjects.headerFactory;
        this.addressFactory = protocolObjects.addressFactory;
        this.sipStack = protocolObjects.sipStack;
        try {
            ListeningPoint listeningPoint = sipStack.createListeningPoint(myAddress, port, "udp");
            this.sipProvider = sipStack.createSipProvider(listeningPoint);
            this.sipProvider.addSipListener(this);
        } catch (Exception ex) {
            throw new RuntimeException("could not create shootme", ex);
        }
    }

    public void processRequest(RequestEvent requestEvent) {
        Request request = requestEvent.getRequest();
        String body = "";
        if (request.getRawContent() != null) {
            body = new String(request.getRawContent(), StandardCharsets.UTF_8);
        }
        String callId = ((CallIdHeader) request.getHeader(CallIdHeader.NAME)).getCallId();
        receivedRequests.add(new Received(request.getMethod(), callId, body));
        logger.logInfo("shootme:" + port + " received " + request.getMethod() + " body=" + body.length());
        if (request.getMethod().equals(Request.ACK)) {
            return;
        }
        try {
            ServerTransaction st = requestEvent.getServerTransaction();
            if (st == null) {
                st = sipProvider.getNewServerTransaction(request);
            }
            Response response = messageFactory.createResponse(Response.OK, request);
            ((ToHeader) response.getHeader(ToHeader.NAME)).setTag("shootme");
            if (request.getMethod().equals(Request.INVITE)) {
                response.addHeader(headerFactory.createContactHeader(
                        addressFactory.createAddress("sip:shootme@" + myAddress + ":" + port)));
            }
            st.sendResponse(response);
        } catch (Exception ex) {
            logger.logError("shootme: could not answer " + request.getMethod(), ex);
        }
    }

    public void processResponse(ResponseEvent responseEvent) {
    }

    public void processTimeout(TimeoutEvent e) {
        logger.logInfo("timeout event:" + e);
    }

    public void processIOException(IOExceptionEvent e) {
        logger.logError("shootme: IOException:" + e);
    }

    public void processTransactionTerminated(TransactionTerminatedEvent e) {
        logger.logInfo("Transaction terminated event recieved:" + e);
    }

    public void processDialogTerminated(DialogTerminatedEvent e) {
        logger.logInfo("dialog terminated event recieved:" + e);
    }

    public Received getReceivedRequest(String method, String callId) {
        synchronized (receivedRequests) {
            for (Received received : receivedRequests) {
                if (received.method.equals(method) && received.callId.equals(callId)) {
                    return received;
                }
            }
        }
        return null;
    }

    public TestAssertion getReceivedRequestAssertion(final String method, final String callId) {
        return new TestAssertion() {
            @Override
            public boolean assertCondition() {
                return getReceivedRequest(method, callId) != null;
            }
        };
    }

    public void stop() {
        sipStack.stop();
    }
}
