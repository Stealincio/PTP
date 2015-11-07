package edu.kit.tm.ptp.test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import edu.kit.tm.ptp.Message;
import edu.kit.tm.ptp.ReceiveListener;
import edu.kit.tm.ptp.raw.Client;
import edu.kit.tm.ptp.raw.Configuration;
import edu.kit.tm.ptp.raw.MessageHandler;
import edu.kit.tm.ptp.raw.TorManager;
import edu.kit.tm.ptp.utility.Constants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class offers JUnit testing for the Client class.
 *
 * @author Simeon Andreev
 *
 */
public class ClientTest {

  /** The minimum length of the message used in the tests. */
  private static final int minMessageLength = 10;
  /** The maximum length of the message used in the tests. */
  private static final int maxMessageLength = 50;


  /** The TorManager for the Tor process used by the first API object. */
  private TorManager manager1 = null;
  /** The TorManager for the Tor process used by the second API object. */
  private TorManager manager2 = null;
  /** The message used in the tests. */
  private String message = null;
  /** The configuration used by the first API object. */
  Configuration configuration1 = null;
  /** The configuration used by the second API object. */
  Configuration configuration2 = null;
  /** The first API object used in the self send test and in the ping-pong test. */
  private Client client1 = null;
  /** The second API object used in the ping-pong test. */
  private Client client2 = null;


  /**
   * @throws IOException Propagates any IOException thrown by the TorManager creation.
   *
   * @see JUnit
   */
  @Before
  public void setUp() throws IOException {
    // Create a RNG.
    RNG random = new RNG();
    // Create a random message within the length bounds.
    message = random.string(minMessageLength, maxMessageLength);

    // Read the configurations.
    try {
      configuration1 = new Configuration(Constants.configfile);
      configuration2 = new Configuration(Constants.configfile);
    } catch (IllegalArgumentException e) {
      // If the configuration file is broken, the test is invalid.
      assertTrue(false);
    } catch (IOException e) {
      // If a failure occurred while reading the configuration file, the test is invalid.
      assertTrue(false);
    }

    // Create the two TorManagers.
    manager1 = new TorManager();
    manager2 = new TorManager();
    // Start the two TorManagers.
    manager1.start();
    manager2.start();

    // Wait (no more than 3 minutes) until the two TorManagers are done with their respective Tor
    // bootstrapping.
    final long start = System.currentTimeMillis();
    while ((!manager1.ready() || !manager2.ready())
        && System.currentTimeMillis() - start < 180 * 1000) {
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        // Sleeping was interrupted. Do nothing.
      }
    }

    if (!manager1.ready() || !manager2.ready()) {
      assertTrue(false);
    }

    configuration1.setTorConfiguration(manager1.directory(), manager1.controlport(),
        manager1.socksport());
    configuration2.setTorConfiguration(manager2.directory(), manager2.controlport(),
        manager2.socksport());
  }

  /**
   * @see JUnit
   */
  @After
  public void tearDown() {
    // Clean up the API objects.
    if (client1 != null) {
      client1.exit(true);
    }
    if (client2 != null) {
      client2.exit(true);
    }
    // Stop the TorManagers.
    manager1.stop();
    manager2.stop();
  }

  /**
   * Test the raw API by sending a message to the local port, and then testing if the same message
   * was received.
   *
   * <p>Fails iff the sent message was not received within a time interval,
   * or if the received message does not match the sent message.
   */
  @Test
  public void testSelfSend() {
    // An atomic boolean used to check whether the sent message was received yet.
    final AtomicBoolean received = new AtomicBoolean(false);
    final AtomicBoolean matches = new AtomicBoolean(false);

    // Create the API object.
    try {
      client1 = new Client(configuration1);
    } catch (IOException e) {
      fail("Caught an IOException while creating the API object: " + e.getMessage());
    }

    // Set the listener.
    client1.listener(new ReceiveListener() {

      @Override
      public void receivedMessage(Message receivedMsg) {
        System.out.println("Received message: " + receivedMsg.content);
        if (!receivedMsg.content.equals(message)) {
          fail("Received message does not match sent message: "
              + message + " != " + receivedMsg.content);
        }
        received.set(true);
        matches.set(receivedMsg.content.equals(message));
      }

    });

    // Create the hidden service identifier.
    String identifier = null;
    try {
      identifier = client1.identifier(true);
    } catch (IOException e) {
      fail("Caught an IOException while creating the hidden service identifier: " + e.getMessage());
    }

    // Wait (no more than 3 minutes) until the API connects to the created identifier.
    long start = System.currentTimeMillis();
    Client.ConnectResponse connect = Client.ConnectResponse.TIMEOUT;
    while (connect == Client.ConnectResponse.TIMEOUT || connect == Client.ConnectResponse.FAIL) {
      try {
        connect = client1.connect(identifier, configuration1.getSocketTimeout());
        Thread.sleep(5 * 1000);
        if (System.currentTimeMillis() - start > 180 * 1000) {
          fail("Connecting to the created identifier took too long.");
        }
      } catch (InterruptedException e) {
        // Waiting was interrupted. Do nothing.
      }
    }

    // Send the message.
    Client.SendResponse sendResponse =
        client1.send(identifier, MessageHandler.wrapRaw(message, Constants.messagestandardflag));
    if (sendResponse != Client.SendResponse.SUCCESS) {
      fail("Sending the message via the client to the created identifier was not successful.");
    }

    // Wait (no more than 3 minutes) until the message was received.
    start = System.currentTimeMillis();
    while (!received.get()) {
      try {
        Thread.sleep(5 * 1000);
        if (System.currentTimeMillis() - start > 180 * 1000) {
          fail("Connecting to the created identifier took too long.");
        }
      } catch (InterruptedException e) {
        // Waiting was interrupted. Do nothing.
      }
    }

    if (!received.get()) {
      fail("Message not received.");
    }

    if (!matches.get()) {
      fail("Received message does not match sent message.");
    }

    // Disconnect the API from the created identifier.
    Client.DisconnectResponse disconnectResponse = client1.disconnect(identifier);
    if (disconnectResponse != Client.DisconnectResponse.SUCCESS) {
      fail("Disconncting the client from the created identifier was not successful.");
    }
  }

  /**
   * Tests the raw API with a ping-pong between two API objects.
   *
   * <p>Fails iff a received message does not match the first sent message, or if the number of
   * received message does not reach the maximum number of messages to receive.
   */
  @Test
  public void testPingPong() {
    // Maximum number of messages received via ping-pong.
    final int max = 25;

    // Create the API objects.
    try {
      client1 = new Client(configuration1);
      client2 = new Client(configuration2);
    } catch (IOException e) {
      fail("Caught an IOException while creating the API objects: " + e.getMessage());
    }

    // Create the hidden service identifiers for the two API objects.
    String i1 = null;
    String i2 = null;

    try {
      i1 = client1.identifier(true);
      i2 = client2.identifier(true);
    } catch (IOException e) {
      fail("Caught an IOException while creating the identifiers: " + e.getMessage());
    }
    // Listeners require final values.
    final String identifier1 = i1;
    final String identifier2 = i2;

    // Connect the two API objects with each other. Wait up to 3 minutes for each connection.
    Client.ConnectResponse connect = Client.ConnectResponse.TIMEOUT;
    long start = System.currentTimeMillis();
    while (connect == Client.ConnectResponse.TIMEOUT || connect == Client.ConnectResponse.FAIL) {
      try {
        connect = client1.connect(identifier2, configuration1.getSocketTimeout());
        Thread.sleep(5 * 1000);
        if (System.currentTimeMillis() - start > 180 * 1000) {
          fail("Connecting first API object to second timed out.");
        }
      } catch (InterruptedException e) {
        // Waiting was interrupted. Do nothing.
      }
    }
    connect = Client.ConnectResponse.TIMEOUT;
    start = System.currentTimeMillis();
    while (connect == Client.ConnectResponse.TIMEOUT || connect == Client.ConnectResponse.FAIL) {
      try {
        connect = client2.connect(identifier1, configuration2.getSocketTimeout());
        Thread.sleep(5 * 1000);
        if (System.currentTimeMillis() - start > 180 * 1000) {
          fail("Connecting second API object to first timed out.");
        }
      } catch (InterruptedException e) {
        // Waiting was interrupted. Do nothing.
      }
    }

    // An atomic counter used check on the number of received messages.
    final AtomicInteger counter = new AtomicInteger(0);

    // Set the listeners.
    client1.listener(new ReceiveListener() {

      @Override
      public void receivedMessage(Message receivedMsg) {
        counter.incrementAndGet();
        if (!receivedMsg.content.equals(message)) {
          fail("First API object received message does not match sent message: "
              + receivedMsg.content + " != " + message);
        }
        client1.send(identifier2,
            MessageHandler.wrapRaw(receivedMsg.content, Constants.messagestandardflag));
      }

    });
    client2.listener(new ReceiveListener() {

      @Override
      public void receivedMessage(Message receivedMsg) {
        if (!receivedMsg.content.equals(message)) {
          fail("Second API object received message does not match sent message: "
              + receivedMsg.content + " != " + message);
        }
        client2.send(identifier1,
            MessageHandler.wrapRaw(receivedMsg.content, Constants.messagestandardflag));
      }

    });
    // Initiate the ping-pong.
    Client.SendResponse sendResponse =
        client1.send(identifier2, MessageHandler.wrapRaw(message, Constants.messagestandardflag));
    if (sendResponse != Client.SendResponse.SUCCESS) {
      fail("Sending first message failed.");
    }

    // Wait (no more than 3 minutes) until the maximum number of received messages is reached.
    start = System.currentTimeMillis();
    while (counter.get() < max && System.currentTimeMillis() - start < 300 * 1000) {
      try {
        Thread.sleep(5 * 1000);
      } catch (InterruptedException e) {
        // Waiting was interrupted. Do nothing.
      }
    }

    if (counter.get() < max) {
      fail("Maximum number of received messages not reached.");
    }

    Client.DisconnectResponse disconnectResponse1 = client1.disconnect(identifier2);
    if (disconnectResponse1 != Client.DisconnectResponse.SUCCESS) {
      fail("Disconnecting first API object failed.");
    }

    Client.DisconnectResponse disconnectResponse2 = client2.disconnect(identifier1);
    if (disconnectResponse2 != Client.DisconnectResponse.SUCCESS) {
      fail("Disconnecting second API object failed.");
    }
  }

}
