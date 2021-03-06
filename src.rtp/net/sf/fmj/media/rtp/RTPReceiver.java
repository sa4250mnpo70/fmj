package net.sf.fmj.media.rtp;

import java.io.IOException;
import java.net.*;

import javax.media.*;
import javax.media.protocol.*;
import javax.media.rtp.*;
import javax.media.rtp.event.*;

import net.sf.fmj.media.*;
import net.sf.fmj.media.protocol.rtp.*;
import net.sf.fmj.media.rtp.util.*;

/**
 * @author Damian Minkov
 * @author Boris Grozev
 * @author Lyubomir Marinov
 */
public class RTPReceiver extends PacketFilter
{
    public class PartiallyProcessedPacketException extends Exception
    {
        public PartiallyProcessedPacketException(String message)
        {
            super(message);
        }
    }

    private class FailedToProcessPacketException extends Exception
    {
        public FailedToProcessPacketException(String message)
        {
            super(message);
        }
    }

    private final SSRCCache cache;
    private final RTPDemultiplexer rtpdemultiplexer;
    private boolean rtcpstarted;
    private static final int MAX_DROPOUT = 3000;
    private static final int MAX_MISORDER = 100;

    //BufferControl initialized
    private boolean initBC = false;
    private final String controlName;

    public RTPReceiver(SSRCCache ssrccache, RTPDemultiplexer rtpdemultiplexer)
    {
        rtcpstarted = false;
        controlName = "javax.media.rtp.RTPControl";
        cache = ssrccache;
        this.rtpdemultiplexer = rtpdemultiplexer;
        setConsumer(null);
        Log.objectCreated(this, "RTPReciever");
        Log.createLink(this, rtpdemultiplexer, "RTPReciever uses RTPDemux");
    }

    @Override
    public String filtername()
    {
        return "RTP Packet Receiver";
    }

    @Override
    public Packet handlePacket(Packet packet)
    {
        return handlePacket((RTPPacket) packet);
    }

    @SuppressWarnings("unused")
    @Override
    public Packet handlePacket(Packet packet, int i)
    {
        return null;
    }

    @SuppressWarnings("unused")
    @Override
    public Packet handlePacket(Packet packet, SessionAddress sessionaddress)
    {
        return null;
    }
    
    /**
     * Handle an RTP packet.
     *
     * @param rtpPacket The packet to process.
     * @return The processed packet. Can be null
     */
    public Packet handlePacket(RTPPacket rtpPacket)
    {
        try
        {
            handleUnsupportedPayloadType(rtpPacket);
            checkNetworkAddress(rtpPacket);
            SSRCInfo ssrcinfo = getSsrcInfo(rtpPacket);
            processCsrcs(rtpPacket);
            initSsrcInfoIfRequired(rtpPacket, ssrcinfo);
            updateStats(rtpPacket, ssrcinfo);
            handleRTCP(rtpPacket);
            ssrcinfo.maxseq = rtpPacket.seqnum;
            performMisMatchedPayloadCheck(rtpPacket, ssrcinfo);
            initializeCurrentFormatIfRequired(rtpPacket, ssrcinfo);
            updateFormatOnDataSourceControl(rtpPacket, ssrcinfo);
            initBufferControlIfRequired(ssrcinfo);
            connectStreamIfRequired(rtpPacket, ssrcinfo);
            fireNewReceiveStreamEventIfRequired(ssrcinfo);
            updateSsrcInfoStats(rtpPacket, ssrcinfo);
            updateQuietStatusIfRequired(ssrcinfo);
            demuxPacket(rtpPacket, ssrcinfo);
        }
        catch (FailedToProcessPacketException e)
        {
            Log.warning(e.getMessage());
            rtpPacket = null;
        }
        catch (PartiallyProcessedPacketException e)
        {
            String message = e.getMessage();
            if (message != null)
            {
                Log.info(message);
            }
        }

        return rtpPacket;
    }

    private void handleUnsupportedPayloadType(RTPPacket rtpPacket) throws PartiallyProcessedPacketException
    {
        if (rtpPacket.payloadType == 13 || rtpPacket.payloadType == 18)
        {
        	//Drop CN and g729 packets without even looking at them (or logging)
            throw new PartiallyProcessedPacketException(null);
        }
    }

    private void demuxPacket(RTPPacket rtpPacket,
                             SSRCInfo ssrcinfo)
    {
        if (ssrcinfo.dsource != null)
        {
            // Demux the actual packet
            SourceRTPPacket sourcertppacket = new SourceRTPPacket(rtpPacket, ssrcinfo);
            rtpdemultiplexer.demuxpayload(sourcertppacket);
        }
    }

    private void updateQuietStatusIfRequired(SSRCInfo ssrcinfo)
    {
        if (ssrcinfo.quiet)
        {
            ssrcinfo.quiet = false;
            ActiveReceiveStreamEvent activereceivestreamevent = null;
            if (ssrcinfo instanceof ReceiveStream)
                activereceivestreamevent = new ActiveReceiveStreamEvent(
                        cache.sm, ssrcinfo.sourceInfo, (ReceiveStream) ssrcinfo);
            else
                activereceivestreamevent = new ActiveReceiveStreamEvent(
                        cache.sm, ssrcinfo.sourceInfo, null);
            cache.eventhandler.postEvent(activereceivestreamevent);
        }
    }

    private void updateSsrcInfoStats(RTPPacket rtpPacket, SSRCInfo ssrcinfo)
    {
        if (ssrcinfo.lastRTPReceiptTime != 0L
                && ssrcinfo.lastPayloadType == rtpPacket.payloadType)
        {
            long l = ((Packet) (rtpPacket)).receiptTime
                    - ssrcinfo.lastRTPReceiptTime;
            l = (l * cache.clockrate[ssrcinfo.payloadType]) / 1000L;
            long l1 = rtpPacket.timestamp - ssrcinfo.lasttimestamp;
            double d = l - l1;
            if (d < 0.0D)
                d = -d;
            ssrcinfo.jitter += 0.0625D * (d - ssrcinfo.jitter);
        }
        ssrcinfo.lastRTPReceiptTime = ((Packet) (rtpPacket)).receiptTime;
        ssrcinfo.lasttimestamp = rtpPacket.timestamp;
        ssrcinfo.payloadType = rtpPacket.payloadType;
        ssrcinfo.lastPayloadType = rtpPacket.payloadType;
        ssrcinfo.bytesreceived += rtpPacket.payloadlength;
        ssrcinfo.lastHeardFrom = ((Packet) (rtpPacket)).receiptTime;
    }

    private void fireNewReceiveStreamEventIfRequired(SSRCInfo ssrcinfo)
    {
        if (!ssrcinfo.newrecvstream)
        {
            NewReceiveStreamEvent newreceivestreamevent = new NewReceiveStreamEvent(
                    cache.sm, (ReceiveStream) ssrcinfo);
            ssrcinfo.newrecvstream = true;
            cache.eventhandler.postEvent(newreceivestreamevent);
        }
    }

    private void connectStreamIfRequired(RTPPacket rtpPacket, SSRCInfo ssrcinfo)
    {
        if (!ssrcinfo.streamconnect)
        {
            net.sf.fmj.media.protocol.rtp.DataSource datasource = (net.sf.fmj.media.protocol.rtp.DataSource) cache.sm.dslist.get(ssrcinfo.ssrc);

            if (datasource == null)
            {
                net.sf.fmj.media.protocol.rtp.DataSource dataSource = cache.sm.getDataSource(null);
                if (dataSource == null)
                {
                    datasource = cache.sm.createNewDS(null);
                    cache.sm.setDefaultDSassigned(ssrcinfo.ssrc);
                }
                else if (!cache.sm.isDefaultDSassigned())
                {
                    datasource = dataSource;
                    cache.sm.setDefaultDSassigned(ssrcinfo.ssrc);
                }
                else
                {
                    datasource = cache.sm.createNewDS(ssrcinfo.ssrc);
                }
            }

            javax.media.protocol.PushBufferStream apushbufferstream[] =
                                                        datasource.getStreams();

            ssrcinfo.dsource = datasource;
            ssrcinfo.dstream = (RTPSourceStream) apushbufferstream[0];
            ssrcinfo.dstream.setFormat(ssrcinfo.currentformat);

            RTPControlImpl rtpControlImpl =
                      (RTPControlImpl) ssrcinfo.dsource.getControl(controlName);

            if (rtpControlImpl != null)
            {
                Format format = cache.sm.formatinfo.get(rtpPacket.payloadType);
                rtpControlImpl.currentformat = format;
                rtpControlImpl.stream = ssrcinfo;
            }

            ssrcinfo.streamconnect = true;
        }

        if (ssrcinfo.dsource != null)
        {
            ssrcinfo.active = true;
        }
    }

    private void updateFormatOnDataSourceControl(RTPPacket rtpPacket,
                                                 SSRCInfo ssrcinfo)
    {
        if (ssrcinfo.dsource != null)
        {
            RTPControlImpl rtpControlImpl =
                       (RTPControlImpl)ssrcinfo.dsource.getControl(controlName);

            if (rtpControlImpl != null)
            {
                Format format = cache.sm.formatinfo.get(rtpPacket.payloadType);
                rtpControlImpl.currentformat = format;
            }
        }
    }

    private void initBufferControlIfRequired(SSRCInfo ssrcinfo)
    {
        if (!initBC)
        {
            ((BufferControlImpl) cache.sm.buffercontrol)
                    .initBufferControl(ssrcinfo.currentformat);
            initBC = true;
        }
    }

    private void initializeCurrentFormatIfRequired(RTPPacket rtpPacket,
                                                   SSRCInfo ssrcinfo) throws PartiallyProcessedPacketException
    {
        if (ssrcinfo.currentformat == null)
        {
            ssrcinfo.currentformat = cache.sm.formatinfo.get(
                                                         rtpPacket.payloadType);
            if (ssrcinfo.currentformat == null)
            {
                throw new PartiallyProcessedPacketException(
                    "No format has been registered for RTP Payload type " +
                    rtpPacket.payloadType);
            }

            if (ssrcinfo.dstream != null)
            {
                ssrcinfo.dstream.setFormat(ssrcinfo.currentformat);
            }
        }
    }

    private void performMisMatchedPayloadCheck(RTPPacket rtpPacket,
                                               SSRCInfo ssrcinfo)
    {
        if (ssrcinfo.lastPayloadType != -1
                && ssrcinfo.lastPayloadType != rtpPacket.payloadType)
        {
            ssrcinfo.currentformat = null;

            if (ssrcinfo.dsource != null)
            {
                RTPControlImpl rtpcontrolimpl = (RTPControlImpl) ssrcinfo.dsource
                        .getControl(controlName);
                if (rtpcontrolimpl != null)
                {
                    rtpcontrolimpl.currentformat = null;
                    rtpcontrolimpl.payload = -1;
                }

                try
                {
                    StringBuffer buf = new StringBuffer("[");
                    for (PushBufferStream aStream : ssrcinfo.dsource.getStreams())
                    {
                        buf.append(aStream.hashCode());
                        buf.append(" ");
                    }

                    buf.append("]");
                    
                    Log.warning("Stopping datasource " + ssrcinfo.dsource.hashCode() + " (used by stream(s) "  + buf.toString() + ")because of payload type "
                            + "mismatch: expecting pt="
                            + ssrcinfo.lastPayloadType + ", got pt="
                            + rtpPacket.payloadType);
                    ssrcinfo.dsource.stop();
                } catch (IOException ioexception)
                {
                    Log.warning("Problem stopping DataSource after payload change "
                            + ioexception.getMessage());
                }
            }

            ssrcinfo.lastPayloadType = rtpPacket.payloadType;

            RemotePayloadChangeEvent remotepayloadchangeevent = new RemotePayloadChangeEvent(
                    cache.sm, (ReceiveStream) ssrcinfo,
                    ssrcinfo.lastPayloadType, rtpPacket.payloadType);
            cache.eventhandler.postEvent(remotepayloadchangeevent);
        }
    }

    private void handleRTCP(RTPPacket rtpPacket)
    {
        if (cache.sm.isUnicast())
            if (!rtcpstarted)
            {
                cache.sm.startRTCPReports(((UDPPacket) rtpPacket.base).remoteAddress);
                rtcpstarted = true;
                byte abyte0[] = cache.sm.controladdress.getAddress();
                int k = abyte0[3] & 0xff;
                if ((k & 0xff) == 255)
                {
                    cache.sm.addUnicastAddr(cache.sm.controladdress);
                }
                else
                {
                    InetAddress inetaddress1 = null;
                    boolean flag2 = true;
                    try
                    {
                        inetaddress1 = InetAddress.getLocalHost();
                    } catch (UnknownHostException unknownhostexception)
                    {
                        flag2 = false;
                    }
                    if (flag2)
                        cache.sm.addUnicastAddr(inetaddress1);
                }
            } else if (!cache.sm
                    .isSenderDefaultAddr(((UDPPacket) rtpPacket.base).remoteAddress))
                cache.sm.addUnicastAddr(((UDPPacket) rtpPacket.base).remoteAddress);
    }

    private void updateStats(RTPPacket rtpPacket, SSRCInfo ssrcinfo)
    {
        int diff = rtpPacket.seqnum - ssrcinfo.maxseq;
        
        if (ssrcinfo.maxseq + 1 != rtpPacket.seqnum && diff > 0)
        {
            ssrcinfo.stats.update(RTPStats.PDULOST, diff - 1);
        }

        //Packets arriving out of order have already been counted as lost (by
        //the clause above), so decrease the lost count.
        if (diff > -MAX_MISORDER && diff < 0)
        {
            ssrcinfo.stats.update(RTPStats.PDULOST, -1);
        }
        
        if (ssrcinfo.wrapped)
        {
            ssrcinfo.wrapped = false;
        }
        
        if (diff < MAX_DROPOUT)
        {
            if (rtpPacket.seqnum < ssrcinfo.baseseq)
            {
                /*
                 * Vincent Lucas: Without any lost, the seqnum cycles when
                 * passing from 65535 to 0. Thus, diff is equal to -65535. But
                 * if there have been losses, diff may be -65534, -65533, etc.
                 * On the other hand, if diff is too close to 0 (i.e. -1, -2,
                 * etc.), it may correspond to a packet out of sequence. This is
                 * why it is a sound choice to differentiate between a cycle and
                 * an out-of-sequence on the basis of a value in between the two
                 * cases i.e. -65535 / 2.
                 */
                if (diff < -65535 / 2)
                {
                    ssrcinfo.cycles += 0x10000;
                    ssrcinfo.wrapped = true;
                }
            }
            ssrcinfo.maxseq = rtpPacket.seqnum;
        } 
        else if (diff <= (65536 - MAX_MISORDER))
        {
            ssrcinfo.stats.update(RTPStats.PDUINVALID);
            if (rtpPacket.seqnum == ssrcinfo.lastbadseq)
                ssrcinfo.initsource(rtpPacket.seqnum);
            else
                ssrcinfo.lastbadseq = rtpPacket.seqnum + 1 & 0xffff;
        } 
        else
        {
            /*
             * TODO Boris Grozev: The case of diff==0 is caught in
             * diff<MAX_DROPOUT and does NOT end up here. Is this the way it is
             * supposed to work?
             */
            ssrcinfo.stats.update(RTPStats.PDUDUP);
        }

        ssrcinfo.received++;
        ssrcinfo.stats.update(RTPStats.PDUPROCSD);
    }

    private void initSsrcInfoIfRequired(RTPPacket rtpPacket, SSRCInfo ssrcinfo)
    {
        if (!ssrcinfo.sender)
        {
            ssrcinfo.initsource(rtpPacket.seqnum);
            ssrcinfo.payloadType = rtpPacket.payloadType;
        }
    }

    private void processCsrcs(RTPPacket rtpPacket)
    {
        //update lastHeardFrom fields in the cache for csrc's
        for (int i = 0; i < rtpPacket.csrc.length; i++)
        {
            SSRCInfo csrcinfo = null;
            if (rtpPacket.base instanceof UDPPacket)
                csrcinfo = cache.get(rtpPacket.csrc[i],
                        ((UDPPacket) rtpPacket.base).remoteAddress,
                        ((UDPPacket) rtpPacket.base).remotePort, 1);
            else
                csrcinfo = cache.get(rtpPacket.csrc[i], null, 0, 1);
            if (csrcinfo != null)
                csrcinfo.lastHeardFrom = ((Packet) (rtpPacket)).receiptTime;
        }
    }

    private SSRCInfo getSsrcInfo(RTPPacket rtpPacket) throws FailedToProcessPacketException
    {
        SSRCInfo ssrcInfo = null;

        if (rtpPacket.base instanceof UDPPacket)
        {
            ssrcInfo = cache.get(rtpPacket.ssrc,
                                 ((UDPPacket) rtpPacket.base).remoteAddress,
                                 ((UDPPacket) rtpPacket.base).remotePort, 1);
        }
        else
        {
            ssrcInfo = cache.get(rtpPacket.ssrc, null, 0, 1);
        }

        if (ssrcInfo == null)
        {
            throw new FailedToProcessPacketException(
                String.format("Dropping RTP packet because ssrcinfo couldn't be obtained " +
                              "from the cache network address. seqnum=%s, ssrc=%s",
                              rtpPacket.seqnum, rtpPacket.ssrc));
        }

        return ssrcInfo;
    }

    private void checkNetworkAddress(RTPPacket rtppacket) throws FailedToProcessPacketException
    {
        if (rtppacket.base instanceof UDPPacket)
        {
            InetAddress inetaddress = ((UDPPacket) rtppacket.base).remoteAddress;
            if (cache.sm.bindtome
                    && !cache.sm.isBroadcast(cache.sm.dataaddress)
                    && !inetaddress.equals(cache.sm.dataaddress))
            {
                throw new FailedToProcessPacketException(
                  String.format("Dropping RTP packet because of a problem with the " +
                                "network address. seqnum=%s", rtppacket.seqnum));
            }
        }
    }
}
