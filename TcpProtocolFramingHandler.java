package de.denic.rri.common;

import static de.denic.rri.common.RriExceptionType.MESSAGE_ENCODING_ILLEGAL;
import static de.denic.rri.common.RriExceptionType.MESSAGE_TOO_LONG;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import org.apache.commons.lang.Validate;
import org.apache.log4j.Logger;

import de.denic.rri.server.monitoring.RriServerLoginCounterWriter;

/**
 * Hilfsklasse für den Umgang mit dem Message-Framing auf dem RRI-Protokoll (UTF-8-kodiert). Die Implementierung ist
 * <strong>nicht</strong> thread-safe! Es sind lediglich Vorkehrungen getroffen, dass ein Schreiben von Daten in den
 * Socket und ein asynchrones Schließen des Sockets serialisiert stattfinden.
 * @version $Revision: 127888 $ ($Date: 2021-02-17 15:33:52 +0100 (Mi, 17. Feb 2021) $) by $Author: simonbr $
 */
public final class TcpProtocolFramingHandler {

  private static final  String NAME_OF_PROTOCOLS_CHARACTER_SET = "UTF-8";


  private static final Logger LOG = Logger.getLogger(TcpProtocolFramingHandler.class);
  // Immutable and thread-safe instance:
  private static final Charset PROTOCOL_CHARACTER_SET = Charset.forName(NAME_OF_PROTOCOLS_CHARACTER_SET);
  private static final int NO_CHECK_OF_MESSAGE_LENGTH = -1;

  // Instance is NOT thread-safe!
  private final CharsetDecoder protocolsCharsetDecoder = PROTOCOL_CHARACTER_SET.newDecoder();
  private final Socket socket;

  private boolean firstFrameSeen;
  private final int defaultTimeoutMillis;
  private final int firstFrameTimeoutMillis;

  private final RriServerLoginCounterWriter rriServerLoginCounterWriter;

  /**
   * @param socket Darf nicht <code>null</code> und muss mit einem Server verbunden (konnektiert) sein.
   */
  public TcpProtocolFramingHandler(final Socket socket, final int defaultTimeoutMillis, final int firstFrameTimeoutMillis,
        final RriServerLoginCounterWriter rriServerLoginCounterWriter) {
    Validate.notNull(socket, "Missing socket");
    this.socket = socket;
    this.defaultTimeoutMillis = defaultTimeoutMillis;
    this.firstFrameTimeoutMillis = firstFrameTimeoutMillis;
    if (firstFrameTimeoutMillis <= 0) {
      // disable dynamic, first frame timeout
      this.firstFrameSeen = true;
    }
    this.rriServerLoginCounterWriter = rriServerLoginCounterWriter;
  }

  /**
   * Methodenaufruf blockiert solange, bis ein neuer Frame eingetroffen ist, oder bis der Stream geschlossen wird.
   * @return Inhalt des nächsten Frames oder <code>null</code>, wenn das Ende des Streams erreicht ist (Connection des
   *         Streams wurde wahrscheinlich geschlossen).
   */
  public MessageDataWithOptionalExceptionValue nextFrame() throws IOException {
    return nextFrame(NO_CHECK_OF_MESSAGE_LENGTH);
  }

  /**
   * Methodenaufruf blockiert solange, bis ein neuer Frame eingetroffen ist, oder bis der Stream geschlossen wird.
   * @param maxAllowedPayloadSize Maximal erlaubte Größe des Frames in Bytes. Wenn eine Zahl kleiner Null angegeben
   *          wird, findet keine Prüfung statt.
   * @return Inhalt des nächsten Frames oder <code>null</code>, wenn das Ende des Streams erreicht ist (Connection des
   *         Streams wurde wahrscheinlich geschlossen).
   */
  public MessageDataWithOptionalExceptionValue nextFrame(final int maxAllowedPayloadSize) throws IOException {
    return doNextFrame(maxAllowedPayloadSize, socket.getInputStream());
  }

  protected final MessageDataWithOptionalExceptionValue doNextFrame(final int maxAllowedPayloadSize,
      final InputStream inputStream) throws IOException {
    
    final long maxTimeMillis;
    if (this.firstFrameSeen) {
      // disable dynamic timeout for later frames
      // use default, fixed read timeout for every read operation
      maxTimeMillis = -1;
      this.socket.setSoTimeout(this.defaultTimeoutMillis);

    } else {
      // use timeout for first frame (login: https://jira-1.osl.denic.de/browse/REGS-701)
      // actual soTimeout will be set before every read operation so the total
      // frame read time can be limited instead of every packet read
      maxTimeMillis = System.currentTimeMillis() + this.firstFrameTimeoutMillis;
    }

    final byte[] messageAsByteArray;
    try {
      // Hinweis: Das Lesen darf nicht synchronisiert werden, da sonst bei blockierten IO-Reads das Schließen des Sockets
      // zu einem Deadlock führen würde, da die close()-Operation synchronisiert NAME_OF_PROTOCOLS_CHARACTER_SETwird (siehe close()).
      final long lengthOfFrameInBytes = evaluateLengthOfFrameInBytes(inputStream, maxTimeMillis);
      if (lengthOfFrameInBytes == 0) {
        return new MessageDataWithOptionalExceptionValue("", null);
      }

      if ((maxAllowedPayloadSize > NO_CHECK_OF_MESSAGE_LENGTH) && (lengthOfFrameInBytes > maxAllowedPayloadSize)) {
        return new MessageDataWithOptionalExceptionValue("[Message to long]", new RriException(MESSAGE_TOO_LONG,
            new Serializable[] {Long.toString(lengthOfFrameInBytes) }), true);
      }

      final int lengthOfFrameInBytesAsInt = (int) lengthOfFrameInBytes;
      messageAsByteArray = new byte[lengthOfFrameInBytesAsInt];
      if (LOG.isDebugEnabled()) {
        LOG.debug("Reading frame of " + lengthOfFrameInBytes + " bytes");
      }
      int bytesReadForMessage = 0;
      while (bytesReadForMessage < lengthOfFrameInBytes) {
        if (maxTimeMillis > 0) {
          this.socket.setSoTimeout(Math.max(1, (int)(maxTimeMillis - System.currentTimeMillis())));
        }
        bytesReadForMessage += inputStream.read(messageAsByteArray, bytesReadForMessage, lengthOfFrameInBytesAsInt
            - bytesReadForMessage);
      }
      if (bytesReadForMessage != lengthOfFrameInBytes) {
        throw new IOException("Expecting frame containing minimum of " + lengthOfFrameInBytes
            + " bytes, but received only " + bytesReadForMessage + " bytes");
      }
    } catch (final SocketTimeoutException ex) {
      if (maxTimeMillis > 0 && this.rriServerLoginCounterWriter != null) {
        // read has strict timeout that is used before a login is received
        // => connection is actively closed because no login is received
        this.rriServerLoginCounterWriter.incrementAndGetTotalClosedAfterNoLoginCount();
      }
      throw ex;
    }

    this.firstFrameSeen = true;
    return decodeMessagesBytes(messageAsByteArray);
  }

  private MessageDataWithOptionalExceptionValue decodeMessagesBytes(final byte[] messageAsByteArray)
      throws IllegalArgumentException {
    try {
      final CharBuffer message = protocolsCharsetDecoder.decode(ByteBuffer.wrap(messageAsByteArray));
      return new MessageDataWithOptionalExceptionValue(message.toString(), null);
    } catch (final CharacterCodingException e) {
      LOG.warn("Decoding received message failed", e);
      return new MessageDataWithOptionalExceptionValue("[Decoding message data failed]", new RriException(
          MESSAGE_ENCODING_ILLEGAL));
    }
  }

  private long evaluateLengthOfFrameInBytes(final InputStream inputStream, final long maxTimeMillis) throws IOException {
    long lengthOfFrameInBytes = 0;
    for (int i = 3; i >= 0; i--) {
      if (maxTimeMillis > 0) {
        this.socket.setSoTimeout(Math.max(1, (int)(maxTimeMillis - System.currentTimeMillis())));
      }
      final int byteRead = inputStream.read();

      if (byteRead < 0) {
        if (i == 3) {
          // No single byte reaches us
          throw new IOException("Input stream of connection is empty: Connection seems to be closed");
        }

        throw new IOException("Missing four bytes representing frame's length");
      }

      lengthOfFrameInBytes += byteRead << (8 * i);
    }
    if ((lengthOfFrameInBytes > Integer.MAX_VALUE) || (lengthOfFrameInBytes < 0)) {
      // Kann eintreten, da bei diesem Algorithmus keine Zahlen < 0 abgebildet werden, aber Zahlen > MAX_VALUE!
      throw new IOException("The four bytes representing frame's length denotes a number that cannot be handled: "
          + lengthOfFrameInBytes);
    }

    return lengthOfFrameInBytes;
  }

  /**
   * Methodenaufruf blockiert solange, bis der übergebene Frame abgesetzt werden konnte.
   */
  public void putFrame(final String frame) throws IOException {
    doPutFrame(frame, socket.getOutputStream());
  }

  /**
   * Access modifier protected for testing purposes
   */
  protected final void doPutFrame(final String frame, final OutputStream outputStream)
      throws UnsupportedEncodingException, IOException {
    final byte[] frameAsUtf8Bytes = frame.getBytes(NAME_OF_PROTOCOLS_CHARACTER_SET);
    final int lengthOfFrameInBytes = frameAsUtf8Bytes.length;
    final byte[] lengthEncodedAsBytes = new byte[] {(byte) (lengthOfFrameInBytes >> 24),
        (byte) (lengthOfFrameInBytes >> 16), (byte) (lengthOfFrameInBytes >> 8), (byte) lengthOfFrameInBytes };
    if (LOG.isDebugEnabled()) {
      LOG.debug("Writing frame of " + lengthOfFrameInBytes + " bytes");
    }
    synchronized (socket) {
      // Schreiben und Schließen (siehe close()) des Sockets werden synchronisiert
      outputStream.write(lengthEncodedAsBytes);
      outputStream.write(frameAsUtf8Bytes);
      outputStream.flush();
    }
  }

  /**
   */
  public void close() {
    if (socket.isClosed()) {
      return;
    }

    try {
      synchronized (socket) {
        // Schreiben (siehe doPutFrame(...)) und Schließen des Sockets werden synchronisiert
        try {
          socket.getOutputStream().flush();
        } finally {
          socket.close();
        }
      }
    } catch (final IOException e) {
      LOG.warn("Closing socket failed", e);
    }
  }


  public InetSocketAddress getSocketAddress() {
    return (InetSocketAddress) socket.getRemoteSocketAddress();
  }

  @Override
  public String toString() {
    return socket.toString();
  }

}