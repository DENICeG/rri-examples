package de.denic.rri.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import org.apache.commons.lang.Validate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Hilfsklasse für den Umgang mit dem Message-Framing auf dem RRI-Protokoll (UTF-8-kodiert). Die Implementierung ist
 * <strong>nicht</strong> thread-safe! Es sind lediglich Vorkehrungen getroffen, dass ein Schreiben von Daten in den
 * Socket und ein asynchrones Schließen des Sockets serialisiert stattfinden.
 * @version $Revision: 1.14 $ ($Date: 2008-10-06 07:39:55 $) by $Author:  $
 */
public final class TcpProtocolFramingHandler implements RriConstants {

  private static final Log log = LogFactory.getLog(TcpProtocolFramingHandler.class);
  // Immutable and thread-safe instance:
  private static final Charset PROTOCOL_CHARACTER_SET = Charset.forName(NAME_OF_PROTOCOLS_CHARACTER_SET);

  // Instance is NOT thread-safe!
  private final CharsetDecoder protocolsCharsetDecoder = PROTOCOL_CHARACTER_SET.newDecoder();
  private final Socket socket;

  /**
   * @param socket Darf nicht <code>null</code> und muss mit einem Server verbunden (konnektiert) sein.
   */
  public TcpProtocolFramingHandler(final Socket socket) throws IllegalArgumentException {
    super();
    Validate.notNull(socket, "Missing socket");
    this.socket = socket;
  }

  /**
   * Methodenaufruf blockiert solange, bis ein neuer Frame eingetroffen ist, oder bis der Stream geschlossen wird.
   * @return Inhalt des nächsten Frames oder <code>null</code>, wenn das Ende des Streams erreicht ist (Connection des
   *         Streams wurde wahrscheinlich geschlossen).
   */
  public MessageDataWithOptionalExceptionValue nextFrame() throws IOException {
    return nextFrame(-1); // Keine Prüfung der erlaubten Länge des Frames
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

  /**
   */
  protected final MessageDataWithOptionalExceptionValue doNextFrame(final int maxAllowedPayloadSize,
      final InputStream inputStream) throws IOException, IllegalArgumentException {
    // Hinweis: Das Lesen darf nicht synchronisiert werden, da sonst bei blockierten IO-Reads das Schließen des Sockets
    // zu einem Deadlock führen würde, da die close()-Operation synchronisiert wird (siehe close()).
    final long lengthOfFrameInBytes = evaluateLengthOfFrameInBytes(inputStream);
    if (lengthOfFrameInBytes == 0) {
      return new MessageDataWithOptionalExceptionValue("", null);
    }

    if ((maxAllowedPayloadSize > 0) && (lengthOfFrameInBytes > maxAllowedPayloadSize)) {
      return new MessageDataWithOptionalExceptionValue("[Message to long]", new RriException(
          RriExceptionType.MESSAGE_TOO_LONG, new Serializable[] {Long.toString(lengthOfFrameInBytes) }), true);
    }

    final int lengthOfFrameInBytesAsInt = (int) lengthOfFrameInBytes;
    final byte[] messageAsByteArray = new byte[lengthOfFrameInBytesAsInt];
    if (log.isInfoEnabled()) {
      log.info("Reading frame of " + lengthOfFrameInBytes + " bytes");
    }
    int bytesReadForMessage = 0;
    while (bytesReadForMessage < lengthOfFrameInBytes) {
      bytesReadForMessage += inputStream.read(messageAsByteArray, bytesReadForMessage, lengthOfFrameInBytesAsInt
          - bytesReadForMessage);
    }
    if (bytesReadForMessage != lengthOfFrameInBytes) {
      throw new IOException("Expecting frame containing minimum of " + lengthOfFrameInBytes
          + " bytes, but received only " + bytesReadForMessage + " bytes");
    }

    return decodeMessagesBytes(messageAsByteArray);
  }

  /**
   */
  private MessageDataWithOptionalExceptionValue decodeMessagesBytes(final byte[] messageAsByteArray)
      throws IllegalArgumentException {
    try {
      final CharBuffer message = protocolsCharsetDecoder.decode(ByteBuffer.wrap(messageAsByteArray));
      return new MessageDataWithOptionalExceptionValue(message.toString(), null);
    } catch (final CharacterCodingException e) {
      log.warn("Decoding received message failed", e);
      return new MessageDataWithOptionalExceptionValue("[Decoding message data failed]", new RriException(
          RriExceptionType.MESSAGE_ENCODING_ILLEGAL));
    }
  }

  /**
   */
  private long evaluateLengthOfFrameInBytes(final InputStream inputStream) throws IOException {
    long lengthOfFrameInBytes = 0;
    for (int i = 3; i >= 0; i--) {
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
    if (log.isInfoEnabled()) {
      log.info("Writing frame of " + lengthOfFrameInBytes + " bytes");
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
      log.warn("Closing socket failed", e);
    }
  }

  /**
   */
  public InetSocketAddress getSocketAddress() {
    return (InetSocketAddress) socket.getRemoteSocketAddress();
  }

  /**
   */
  @Override
  public String toString() {
    return socket.toString();
  }

}
