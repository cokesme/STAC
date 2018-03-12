package stac.communications.handlers;

import stac.client.Screen;
import stac.communications.CONNECTION_PHASE;
import stac.communications.Handler;
import stac.communications.PACKETS;
import stac.communications.Packet;
import stac.communications.PacketBuffer;
import stac.communications.PacketParserException;
import stac.communications.Session;
import stac.communications.packets.RequestPacket;
import stac.parser.OpenSSLRSAPEM;

import java.io.IOException;

import static stac.communications.CONNECTION_PHASE.FAILED;
import static stac.communications.CONNECTION_PHASE.HANDSHAKE_ACCEPTED;

/**
 * This handler is designed to handle requests (messages and termination packets).
 */
public class RequestHandler extends Handler {
    private Screen screen;

    public RequestHandler(Screen screen) {
        this.screen = screen;
    }

    @Override
    /*
     * 8     8     8     8
     * |-----|-----|-----|-----| 32
     * |-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----| 128
     * Request Packet:
     * |pType|- Session Content Len -|--------------- sender fingerprint - (256) ----------------------|
     * |-----------------------------------------------------------------------------------------------|
     * |-----------------------------|-------------- receiver fingerprint - (256) ---------------------|
     * |-----------------------------------------------------------------------------------------------|
     * |-----------------------------|---- Nonce Length -----|-- RSA encrypted nonce bytes --- ~~ -----|
     * |--- Counter Length ----|------------------------------- RSA encrypted counter bytes -- ~~ -----|
     */
    protected boolean isPacketFormed(PacketBuffer packetBuffer) {
        if (packetBuffer.getOffset() >= getMinPacketSize()) {
            Integer nonceLength = OpenSSLRSAPEM.INTEGER.valueOfUnsigned(packetBuffer.getBuffer(), 69, 4).getInternal();
            if (packetBuffer.getOffset() >= 73 + nonceLength) {
                Integer counterLength = OpenSSLRSAPEM.INTEGER.valueOfUnsigned(packetBuffer.getBuffer(), 69 + 4 + nonceLength, 4).getInternal();
                if (packetBuffer.getOffset() >= 77 + nonceLength + counterLength) {
                    Integer sessionContentLength = OpenSSLRSAPEM.INTEGER.valueOfUnsigned(packetBuffer.getBuffer(), 1, 4).getInternal();
                    return packetBuffer.getOffset() == 77 + nonceLength + counterLength + 32 + sessionContentLength;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean isPacketStillOK(PacketBuffer packetBuffer) {
        return true;
    }

    @Override
    protected int getMaxPacketSize() {
        return PACKETS.REQUEST.maxSize();
    }

    @Override
    protected int getMinPacketSize() {
        return PACKETS.REQUEST.minSize();
    }

    /**
     * Runs the packet parser associated with this Handler.
     * <p>
     * This RESETS the packetBuffer.
     *
     * @param packetBuffer The read in packet.
     * @param session
     * @return The Packet produced by the parser.
     */
    @Override
    public Packet runPacketParser(PacketBuffer packetBuffer, Session session, PACKETS expecting) throws PacketParserException {
        if (expecting == PACKETS.REQUEST) {
            RequestPacket packet = (RequestPacket) RequestPacket.newRawRequestPacket(session).getParser().parse(packetBuffer, session.getRemote());
            if (packet.getReceiverKey().matches(session.getServerKey())) {
                packet.getReceiverKey().setPem(session.getServerKey().getPem());
            }
            packetBuffer.reset();
            return packet;
        } else {
            throw new RuntimeException("Request Handler: Something is not implemented");
        }
    }

    /**
     * The packet is handled with this method and the next connection state is returned by it.
     *
     * @param packetBuffer The packet buffer containing a packet which is ready to be parsed and acted upon.
     * @param session      The session that this Handshake handler belongs to.
     * @return The next connection state.
     */
    @Override
    public CONNECTION_PHASE handlePacket(PacketBuffer packetBuffer, Session session) {
        try {
            if (session.isExpecting(PACKETS.REQUEST)) {
                RequestPacket packet = (RequestPacket) runPacketParser(packetBuffer, session, session.getExpecting());
                packet.setCommunications(session.getCommunications());

                if (screen != null && packet.getType() == RequestPacket.RequestType.Message) {
                    screen.postMessage(packet);
                    terminate(session, packet);
                } else if (screen != null && packet.getType() == RequestPacket.RequestType.Terminate) {
                    return CONNECTION_PHASE.TERMINATING;
                } else {
                    System.err.println("Request handler cannot handle this message.");
                    return FAILED;
                }

                return HANDSHAKE_ACCEPTED;
            } else {
                System.err.println("Request handler invoked, but session is not expecting a request packet. Closing session.");
                return FAILED;
            }
        } catch (PacketParserException e) {
            System.err.println("A(n) " + e.getClass().getName() + " Exception has occurred. Closing session.");
            return FAILED;
        } catch (IOException e) {
            System.err.println("Reply read failed.");
            return FAILED;
        }
    }

    private void terminate(Session session, RequestPacket packet) throws PacketParserException, IOException {
        session.send(RequestPacket.newTermination(session.getCommunications().getListenerKey(), packet, session));
    }
}

