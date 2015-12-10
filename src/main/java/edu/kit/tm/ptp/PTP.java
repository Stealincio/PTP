package edu.kit.tm.ptp;

import edu.kit.tm.ptp.raw.Configuration;
import edu.kit.tm.ptp.raw.ExpireListener;
import edu.kit.tm.ptp.raw.TorManager;
import edu.kit.tm.ptp.raw.connection.TTLManager;
import edu.kit.tm.ptp.serialization.Serializer;
import edu.kit.tm.ptp.serialization.Message;
import edu.kit.tm.ptp.utility.Constants;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Wrapper class for the PeerTorPeer raw API. Provides the following on top of the raw API: *
 * automatic socket management * sets configuration parameters * uses a shared Tor instance
 *
 * @author Simeon Andreev
 *
 */
public class PTP implements SendListener, ReceiveListener {


  /**
   * A response enumeration for the send method. * SUCCESS indicates a successfully sent message. *
   * CLOSED indicates that the socket connection is not open. * FAIL indicates a failure when
   * sending the message.
   */
  public enum SendResponse {
    SUCCESS, TIMEOUT, FAIL
  }


  /** The logger for this class. */
  private final Logger logger;
  /** The configuration of the client. */
  private final Configuration config;
  /** The Tor process manager. */
  private final TorManager tor;
  /** The manager that closes sockets when their TTL expires. */
  private final TTLManager manager;
  /** A dummy sending listener to use when no listener is specified upon message sending. */
  private SendListener sendListener = new SendListenerAdapter();
  private ReceiveListener receiveListener = new ReceiveListenerAdapter();
  /** The identifier of the currently used hidden service. */
  private Identifier current = null;
  /** Indicates whether this API wrapper should reuse a hidden service. */
  private final boolean reuse;

  private final HiddenServiceConfiguration hiddenServiceConfig;
  private final ConnectionManager connectionManager;
  private int hiddenServicePort;
  private final Serializer serializer = new Serializer();
  private ListenerContainer listeners = new ListenerContainer();

  /**
   * Constructor method. Creates an API wrapper which manages a Tor process.
   *
   * @throws IOException Propagates any IOException thrown by the construction of the raw API, the
   *         configuration, or the Tor process manager.
   *
   * @see Client
   * @see Configuration
   * @see TorManager
   */
  public PTP() throws IOException {
    this(null);
  }

  /**
   * Constructor method. Creates an API wrapper which manages a Tor process.
   *
   * @param directory The name of the hidden service to reuse. May be null to indicate no specific
   *        reuse request.
   * @throws IOException Propagates any IOException thrown by the construction of the raw API, the
   *         configuration, or the Tor process manager.
   *
   * @see Client
   * @see Configuration
   * @see TorManager
   */
  public PTP(String directory) throws IOException {
    // Read the configuration.
    config = new Configuration(Constants.configfile);
    // Create the logger after the configuration sets the logger properties file.
    logger = Logger.getLogger(Constants.ptplogger);

    // Create the Tor process manager and start the Tor process.
    tor = new TorManager();
    // Start the Tor process.
    tor.start();

    // Did not receive a hidden service directory to reuse.
    reuse = directory != null;

    // Wait until the Tor bootstrapping is complete.
    final long start = System.currentTimeMillis();
    final long timeout = config.getTorBootstrapTimeout();

    logger.log(Level.INFO, "Waiting for Tors bootstrapping to finish.");
    while (!tor.ready() && tor.running() && System.currentTimeMillis() - start < timeout) {
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        // Waiting was interrupted. Do nothing.
      }
    }

    // Check if Tor is not running.
    if (!tor.running()) {
      throw new IllegalArgumentException("Starting Tor failed!");
    }

    // Check if we reached the timeout without a finished boostrapping.
    if (!tor.ready()) {
      tor.killtor();
      throw new IllegalArgumentException("Tor bootstrapping timeout expired!");
    }

    // Set the control ports.
    config.setTorConfiguration(tor.directory(), tor.controlport(), tor.socksport());

    // Create the client with the read configuration and set its receiving listener.
    // client = new Client(config, directory);

    connectionManager = new ConnectionManager();
    hiddenServicePort = connectionManager.startBindServer();

    hiddenServiceConfig = new HiddenServiceConfiguration(config, directory, hiddenServicePort);


    // Create and start the manager with the given TTL.
    manager = new TTLManager(getTTLManagerListener(), config.getTTLPoll());
    manager.start();

    // Create and start the message dispatcher.
    // dispatcher = new MessageDispatcher(getMessageDispatcherListener(),
    // config.getDispatcherThreadsNumber(), config.getSocketTimeout());
  }

  /**
   * Constructor method. Creates an API wrapper which uses a Tor process running outside the API.
   *
   * @param workingDirectory The working directory of the Tor process.
   * @param controlPort The control port of the Tor process.
   * @param socksPort The SOCKS port of the Tor process.
   * @throws IOException Propagates any IOException thrown by the construction of the raw API, the
   *         configuration, or the Tor process manager.
   *
   * @see Client
   * @see Configuration
   */
  public PTP(String workingDirectory, int controlPort, int socksPort) throws IOException {
    this(workingDirectory, controlPort, socksPort, Constants.anyport, null);
  }

  /**
   * Constructor method. Creates an API wrapper which uses a Tor process running outside the API.
   *
   * @param workingDirectory The working directory of the Tor process.
   * @param controlPort The control port of the Tor process.
   * @param socksPort The SOCKS port of the Tor process.
   * @param localPort The port on which the local hidden service should run.
   * @param directory The name of the hidden service to reuse. May be null to indicate no specific
   *        reuse request.
   * @throws IOException Propagates any IOException thrown by the construction of the raw API, the
   *         configuration, or the Tor process manager.
   *
   * @see Client
   * @see Configuration
   */
  public PTP(String workingDirectory, int controlPort, int socksPort, int localPort,
      String directory) throws IOException {
    // Read the configuration.
    config = new Configuration(workingDirectory + "/" + Constants.configfile);
    // Create the logger after the configuration sets the logger properties file.
    logger = Logger.getLogger(Constants.ptplogger);

    // We will use an already running Tor instance, instead of managing one.
    tor = null;

    // Set the control ports.
    config.setTorConfiguration(workingDirectory, controlPort, socksPort);
    // Create the client with the read configuration and set its hidden service directory if given.
    reuse = directory != null;

    // client = new Client(config, localPort, directory);
    connectionManager = new ConnectionManager();
    hiddenServicePort = connectionManager.startBindServer();

    hiddenServiceConfig = new HiddenServiceConfiguration(config, directory, hiddenServicePort);

    // Create and start the manager with the given TTL.
    manager = new TTLManager(getTTLManagerListener(), config.getTTLPoll());
    manager.start();
    // Create and start the message dispatcher.
    // dispatcher = new MessageDispatcher(getMessageDispatcherListener(),
    // config.getDispatcherThreadsNumber(), config.getSocketTimeout());
  }


  /**
   * Returns the currently used API configuration.
   *
   * @return The currently used API configuration.
   */
  public Configuration getConfiguration() {
    return config;
  }

  /**
   * Returns the currently used hidden service identifier.
   *
   * @return The hidden service identifier of the used/created hidden service.
   *
   * @see Client
   */
  public Identifier getIdentifier() {
    return current;
  }

  /**
   * Creates a hidden service, if possible reuses the hidden service indicated at API wrapper
   * creation.
   *
   * @throws IOException Propagates any IOException the API received while creating the hidden
   *         service.
   *
   * @see Client
   */
  public void reuseHiddenService() throws IOException {
    reuseHiddenService(false);
  }

  /**
   * Creates a hidden service, if possible reuses the hidden service indicated at API wrapper
   * creation.
   *
   * @param renew A switch stating whether the Tor process should be contacted for the hidden
   *        service creation. Set to true if the Tor server was restarted without closing the
   *        client.
   * @throws IOException Propagates any IOException the API received while creating the hidden
   *         service.
   *
   * @see Client
   */
  public void reuseHiddenService(boolean renew) throws IOException {
    if (renew || current == null) {
      current = new Identifier(hiddenServiceConfig.identifier(!reuse));
    }
  }

  /**
   * Creates a fresh hidden service.
   *
   * @throws IOException Propagates any IOException the API received while creating the hidden
   *         service.
   *
   * @see Client
   */
  public void createHiddenService() throws IOException {
    // Create a fresh hidden service identifier.
    current = new Identifier(hiddenServiceConfig.identifier(true));
  }

  public long sendMessage(byte[] data, Identifier destination, long timeout) {
    return sendMessage(data, destination);
  }

  public long sendMessage(byte[] data, Identifier destination) {
    Message msg = new Message(data);
    return sendMessage(msg, destination);
  }

  public long sendMessage(Object message, Identifier destination) {
    byte[] data = serializer.serialize(message);
    
    return connectionManager.send(data, destination);
  }

  public long sendMessage(Object message, Identifier destination, long timeout) {
    return sendMessage(message, destination);
  }

  public <T> void registerMessage(Class<T> type, MessageReceivedListener<T> listener) {
    serializer.registerClass(type);
    listeners.putListener(type, listener);
  }

  public void setReceiveListener(ReceiveListener listener) {
    this.receiveListener = listener;
  }

  public void setSendListener(SendListener listener) {
    this.sendListener = listener;
  }

  /**
   * Returns the local port on which the local hidden service is available.
   *
   * @return The local port on which the local hidden service is available.
   *
   * @see Client
   */
  public int getLocalPort() {
    // Get the local port from the client.
    // return client.localPort();
    return hiddenServicePort;
  }

  /**
   * Closes the local socket and any open connections. Stops the socket TTL manager and the Tor
   * process manager.
   *
   * @see Client
   */
  public void exit() {
    connectionManager.stop();

    // Close the socket TTL manager.
    manager.stop();

    try {
      hiddenServiceConfig.deleteHiddenService();
    } catch (IOException ioe) {
      logger.log(Level.WARNING,
          "Received IOException while deleting the hidden service directory: " + ioe.getMessage());
    }

    // Close the Tor process manager.
    if (tor != null) {
      tor.stop();
    }
  }


  /**
   * Returns a manager listener that will close socket connections with expired TTL.
   *
   * @return The manager listener which closes sockets with no TTL left.
   */
  private ExpireListener getTTLManagerListener() {
    return new ExpireListener() {

      /**
       * Set the listener to disconnect connections with expired TTL.
       *
       * @param identifier The identifier with the expired socket connection.
       *
       * @see TTLManager.Listener
       */
      @Override
      public void expired(Identifier identifier) throws IOException {
        // client.send(identifier.getTorAddress(),
        // MessageHandler.wrapRaw("", Constants.messagedisconnectflag));
        connectionManager.disconnect(identifier);
      }

    };
  }

  @Override
  public void messageReceived(byte[] data, Identifier source) {
    Object message;
    try {
      message = serializer.deserialize(data);
      
      listeners.callListener(message, source);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public void messageSent(long id, Identifier destination, State state) {
    // TODO Auto-generated method stub

  }

}