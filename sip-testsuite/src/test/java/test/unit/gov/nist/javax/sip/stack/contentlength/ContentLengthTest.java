package test.unit.gov.nist.javax.sip.stack.contentlength;

import java.util.Arrays;
import java.util.Collection;

import javax.sip.message.Request;
import javax.sip.message.Response;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import gov.nist.javax.sip.stack.transports.processors.netty.NettyMessageProcessorFactory;
import gov.nist.javax.sip.stack.transports.processors.nio.NioMessageProcessorFactory;
import gov.nist.javax.sip.stack.transports.processors.oio.OIOMessageProcessorFactory;
import test.tck.msgflow.callflows.AssertUntil;
import test.tck.msgflow.callflows.NetworkPortAssigner;
import test.unit.gov.nist.javax.sip.stack.contentlength.Shootme.Received;

/**
 * Content-Length of requests over UDP, each test on Netty, OIO and NIO. RFC 3261 18.3: without
 * Content-Length the body ends with the datagram, a Content-Length past the end of the datagram
 * gets 400. A value that is not 1*DIGIT (25.1) gets 400
 * <p>
 */
@RunWith(Parameterized.class)
public class ContentLengthTest {

    private static final int TIMEOUT = 5000;

    @Parameters(name = "{0}")
    public static Collection<Object[]> messageProcessorFactories() {
        return Arrays.asList(new Object[][] {
                { "Netty", NettyMessageProcessorFactory.class },
                { "OIO", OIOMessageProcessorFactory.class },
                { "NIO", NioMessageProcessorFactory.class } });
    }

    private final Class<?> messageProcessorFactory;

    private int shootistPort;
    private int shootmePort;

    private Shootist shootist;
    private Shootme shootme;

    public ContentLengthTest(String name, Class<?> messageProcessorFactory) {
        this.messageProcessorFactory = messageProcessorFactory;
    }

    @Before
    public void setUp() throws Exception {
        shootistPort = NetworkPortAssigner.retrieveNextPort();
        shootmePort = NetworkPortAssigner.retrieveNextPort();
        shootme = new Shootme(shootmePort, messageProcessorFactory.getName());
        shootist = new Shootist(shootistPort, shootmePort);
    }

    @After
    public void tearDown() throws Exception {
        if (shootist != null) {
            shootist.stop();
        }
        if (shootme != null) {
            shootme.stop();
        }
    }

    /** Content-Length: 11, 11 bytes of body. Control: a missing delivery or 400 elsewhere is real */
    @Test(timeout = 30000)
    public void testContentLength() throws Exception {
        shootist.sendMessage("Content-Length: 11\r\n");
        assertDelivered(Request.MESSAGE, Shootist.BODY);
        Assert.assertTrue("No 200 OK arrived at the sender",
                AssertUntil.assertUntil(shootist.getResponseAssertion(Response.OK), TIMEOUT));
        assertStillListening();
    }

    /** compact form, l: 11 */
    @Test(timeout = 30000)
    public void testCompactContentLength() throws Exception {
        shootist.sendMessage("l: 11\r\n");
        assertDelivered(Request.MESSAGE, Shootist.BODY);
        assertStillListening();
    }

    /** ACK for the 2xx without Content-Length */
    @Test(timeout = 30000)
    public void testAckWithoutContentLength() throws Exception {
        shootist.sendInvite();
        Assert.assertTrue("No 200 OK to the INVITE arrived at the sender",
                AssertUntil.assertUntil(shootist.getResponseAssertion(Response.OK), TIMEOUT));
        // RFC 3261 18.3: the body ends with the datagram, empty here
        assertDelivered(Request.ACK, "");
        assertStillListening();
    }

    /** Content-Length: 20, 11 bytes of body */
    @Test(timeout = 30000)
    public void testContentLengthPastEndOfDatagram() throws Exception {
        shootist.sendMessage("Content-Length: 20\r\n");
        // RFC 3261 18.3: the datagram ends before the body, a request SHOULD get 400
        assertRejected(Request.MESSAGE);
        assertStillListening();
    }

    /** Content-Length: abc */
    @Test(timeout = 30000)
    public void testContentLengthNotANumber() throws Exception {
        shootist.sendOptions("Content-Length: abc\r\n");
        // RFC 3261 25.1: Content-Length is 1*DIGIT
        assertRejected(Request.OPTIONS);
        assertStillListening();
    }

    /** Content-Length: -1 */
    @Test(timeout = 30000)
    public void testNegativeContentLength() throws Exception {
        shootist.sendOptions("Content-Length: -1\r\n");
        // RFC 3261 25.1: Content-Length is 1*DIGIT
        assertRejected(Request.OPTIONS);
        assertStillListening();
    }

    /** Content-Length : 11, 11 bytes of body */
    @Test(timeout = 30000)
    public void testSpaceBeforeColon() throws Exception {
        shootist.sendMessage("Content-Length : 11\r\n");
        // RFC 3261 25.1: HCOLON allows whitespace before the colon
        assertDelivered(Request.MESSAGE, Shootist.BODY);
        assertStillListening();
    }

    /** extension header whose name starts with l, then Content-Length: 0 */
    @Test(timeout = 30000)
    public void testHeaderStartingWithL() throws Exception {
        shootist.sendOptions("Lang-Pref: en\r\n" + "Content-Length: 0\r\n");
        // only l alone is the compact Content-Length
        assertDelivered(Request.OPTIONS, "");
        assertStillListening();
    }

    //reaches the application with that body
    private void assertDelivered(String method, String body) throws Exception {
        AssertUntil.assertUntil(shootme.getReceivedRequestAssertion(method, Shootist.CALL_ID), TIMEOUT);
        Received received = shootme.getReceivedRequest(method, Shootist.CALL_ID);
        Assert.assertNotNull("Application got no " + method, received);
        Assert.assertEquals(method + " body", body, received.body);
    }

    //400 back, the application never sees it
    private void assertRejected(String method) throws Exception {
        AssertUntil.assertUntil(shootist.getResponseAssertion(Response.BAD_REQUEST), TIMEOUT);
        Received received = shootme.getReceivedRequest(method, Shootist.CALL_ID);
        if (received != null) {
            Assert.fail("Application got the " + method + " with body '" + received.body + "'");
        }
        Assert.assertNotNull("No 400 Bad Request arrived at the sender", shootist.getResponse(Response.BAD_REQUEST));
    }

    //a well framed OPTIONS still reaches the application
    private void assertStillListening() throws Exception {
        shootist.sendWellFramedOptions();
        Assert.assertTrue("Application got no well framed OPTIONS after the flow", AssertUntil.assertUntil(
                shootme.getReceivedRequestAssertion(Request.OPTIONS, Shootist.WELL_FRAMED_CALL_ID), TIMEOUT));
    }
}
