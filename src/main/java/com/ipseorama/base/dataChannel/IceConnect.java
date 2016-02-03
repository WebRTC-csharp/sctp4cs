/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ipseorama.base.dataChannel;

import com.ipseorama.base.certHolders.CertHolder;
import com.ipseorama.base.certHolders.JksCertHolder;
import com.ipseorama.sctp.Association;
import com.ipseorama.sctp.AssociationListener;
import com.ipseorama.sctp.leakey.LeakyAssociation;
import com.ipseorama.sctp.small.ThreadedAssociation;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.NominationStrategy;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;
import com.phono.srtplight.Log;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bouncycastle.crypto.tls.DTLSTransport;
import org.bouncycastle.crypto.tls.DatagramTransport;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidatePairState;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.socket.DTLSDatagramFilter;
import org.ice4j.socket.IceSocketWrapper;
import org.ice4j.socket.MultiplexingDatagramSocket;

/**
 *
 * @author tim
 */
public class IceConnect implements PropertyChangeListener {

    private long startTime;
    protected Agent _localAgent;
    private static CertHolder _cert;
    private AssociationListener _al;
    private String _ffp;
    public Runnable cleanup;
    private DTLSEndpoint _dtls;
    protected boolean _dtlsClientRole = true; // by default we would rather be the DTLS client
    private boolean _wantToStartIce;
    protected boolean _haveLocalCandy;
    protected boolean _haveRemoteCandy;
    private boolean _iceStarted;
    protected boolean _offerer;

    public void setAssociationListener(AssociationListener al) {
        this._al = al;
    }

    IceConnect(int port) throws IOException, UnrecoverableEntryException, KeyStoreException, FileNotFoundException, NoSuchAlgorithmException, CertificateException {
        this(port, null);
    }

    IceConnect(int port, CertHolder certH) throws IOException, UnrecoverableEntryException, KeyStoreException, FileNotFoundException, NoSuchAlgorithmException, CertificateException {
        _cert = (certH == null) ? new JksCertHolder() : certH;
        _ffp = null;
        _localAgent = createAgent(true);
        _localAgent.addStateChangeListener(this);
        _localAgent.setTa(250);

        _localAgent.setNominationStrategy(
                NominationStrategy.NOMINATE_FIRST_VALID);

        //let them fight ... fights forge character.
        _localAgent.setControlling(!this._dtlsClientRole);
        _localAgent.setPerformConsentFreshness(true);
        //STREAMS
        createStream(port, "data", _localAgent);

    }
    /*
     void addRemoteCandidate( ) {

     List<Component> localComponents = localStream.getComponents();

     for (Component localComponent : localComponents) {
     int id = localComponent.getComponentID();

     // localComponent.setDefaultRemoteCandidate(remoteComponent.getDefaultCandidate());
     localComponent.addRemoteCandidate(new RemoteCandidate(
     rCand.getTransportAddress(),
     localComponent,
     rCand.getType(),
     rCand.getFoundation(),
     rCand.getPriority(),
     null));

     }
     }
     */

    private void tryToStartIce() {
        if (_wantToStartIce && _haveRemoteCandy && _haveLocalCandy && !_iceStarted) {
            _iceStarted = true;
            _localAgent.startConnectivityEstablishment();
            Log.debug("Actually starting Ice ");

        } else {
            if (_iceStarted) {
                Log.debug("Ice already started ");
            } else {
                Log.debug("cant start Ice yet " + _wantToStartIce + " " + _haveRemoteCandy + " " + _haveLocalCandy);
            }
        }
    }

    public void startIce() {
        _wantToStartIce = true;
        tryToStartIce();
    }

    void buildIce(String ufrag, String upass) throws InterruptedException, IllegalArgumentException, IOException {
        startTime = System.currentTimeMillis();

        long endTime = System.currentTimeMillis();

        //transferRemoteCandidates(localAgent, remotePeer);
        for (IceMediaStream stream : _localAgent.getStreams()) {
            stream.setRemoteUfrag(ufrag);
            stream.setRemotePassword(upass);
        }

        Log.info("Total candidate gathering time: "
                + (endTime - startTime) + "ms");
        Log.info("LocalAgent:\n" + _localAgent);


        /* Log.info("Local audio clist:\n"
         + _localAgent.getStream("audio").getCheckList());*/
    }

    String getUfrag() {
        return _localAgent.getLocalUfrag();
    }

    String getPass() {
        return _localAgent.getLocalPassword();
    }

    public String getPrint() {
        String ret = "";
        try {
            ret = _cert.getPrint();
        } catch (IOException ex) {
            Log.debug("cant get fingerprint" + ex.toString());
        }
        return ret;
    }

    protected void setFarFingerprint(String ffp) {
        _ffp = ffp;
    }

    String getFarFingerprint() {
        return _ffp;
    }

    protected void haveLocalCandy(boolean have) {
        _haveLocalCandy |= have;
        if (_haveLocalCandy) {
            tryToStartIce();
        }
    }

    protected void haveRemoteCandy(boolean have) {
        _haveRemoteCandy |= have;
        if (_haveRemoteCandy) {
            tryToStartIce();
        }
    }

    public List<Candidate> getCandidates() {
        ArrayList<Candidate> ret = new ArrayList();
        IceMediaStream st = getStream("data");
        if (st != null) {
            List<Component> a = st.getComponents();
            if (a != null) {
                for (Component comp : a) {
                    for (Candidate candy : comp.getLocalCandidates()) {
                        Log.debug("adding " + candy);
                        ret.add(candy);
                    }
                }
            }
        }
        haveLocalCandy(ret.size() > 0);
        return ret;
    }

    public DatagramTransport mkTransport(DatagramSocket lds, TransportAddress rta) {
        final IceConnect ic = this;
        return new QueuingDatagramTransport(lds, rta) {
            @Override
            public void close() throws IOException {
                Log.debug("close called on Datagram transport");
                //- probably closed due to a dtls alert -
                //- in which case kill the association and dump the data
                //- we shouldn't expect any progress from here on out....
                super.close();
                ic.freeAgent();
            }
        };
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        Log.debug("got PCE evt on " + evt.getPropertyName() + " value is now " + evt.getNewValue());
        if (evt.getPropertyName().equals("IceProcessingState")) {
            IceProcessingState st = (IceProcessingState) evt.getNewValue();

            switch (st) {
                case COMPLETED:
                    Log.debug("Ice " + st.name());
                    synchronized (this) {
                        IceMediaStream s = getStream("data");

                        Component comp = s.getComponent(1);
                        final CandidatePair cp = comp.getSelectedPair();

                        IceSocketWrapper isw = cp.getIceSocketWrapper();
                        TransportAddress rta = cp.getRemoteCandidate().getTransportAddress();

                        DatagramSocket lds = cp.getDatagramSocket();
                        Log.debug("lds is of type " + lds.getClass().getName());
                        if (lds instanceof MultiplexingDatagramSocket) {
                            try {
                                lds = ((MultiplexingDatagramSocket) lds).getSocket(new DTLSDatagramFilter());
                            } catch (SocketException ex) {
                                Log.error("cant make DTLS MultiplexedDatagramSocket because: " + ex.toString());
                            }
                        }
                        Log.debug("new lds is of type " + lds.getClass().getName());
                        // todo - might not be UDP in future - 
                        Log.debug("selected Ice socket" + isw.toString());
                        if (lds.isBound()) {
                            Log.debug("local ds bound to " + lds.getLocalSocketAddress());
                        } else {
                            Log.debug("local ds not bound. Relay?" + lds.getLocalSocketAddress());
                            //break;
                        }
                        if (rta != null) {
                            Log.debug("remote transport address " + rta.toString());
                        } else {
                            Log.debug("remote rta not set bailing..." + cp.getRemoteCandidate());
                            break;
                        }
                        if (_dtls != null) {
                            Log.error("Duplicate  completion we have a DTLS service so now what ??? ");
                            break;
                        }
                        try {
                            final DatagramTransport ds = mkTransport(lds, rta);
                            PropertyChangeListener pcl = new PropertyChangeListener() {
                                @Override
                                public void propertyChange(PropertyChangeEvent evt) {
                                    Log.verb("pce on candidate pair " + evt.toString());
                                    CandidatePair src = (CandidatePair) evt.getSource();
                                    if ((src == cp) && (IceMediaStream.PROPERTY_PAIR_STATE_CHANGED.equals(evt.getPropertyName()))) {
                                        CandidatePairState oldv = (CandidatePairState) evt.getOldValue();
                                        CandidatePairState newv = (CandidatePairState) evt.getNewValue();
                                        if ((oldv == CandidatePairState.SUCCEEDED) && (newv == CandidatePairState.FAILED)) {
                                            Log.debug("Selected ICE pair newly failed - closing Transport ");
                                            try {
                                                ds.close();
                                                // todo - really we should look if there is a new selected candidate
                                                // if there is we should plumb that into the dtls layer (invisibly)
                                                // but we dont. Yet.
                                            } catch (IOException ex) {
                                                Log.debug("can't close transport: " + ex.getMessage());
                                            }
                                        }
                                    } else {
                                        Log.debug("Not the ICE pair we are looking for");
                                    }
                                }
                            };
                            s.addPairChangeListener(pcl);
                            Log.debug("DTLS role " + (_dtlsClientRole ? "client" : "server"));
                            final boolean offerer = _offerer;
                            Log.debug("SDP role  " + (_offerer ? "offer" : "answer"));

                            if (_dtlsClientRole) {
                                _dtls = new DTLSClient(_cert, ds, _al, _ffp) {
                                    @Override
                                    public boolean shouldInitiateAssociation() {
                                        return offerer;
                                    }

                                    @Override
                                    public Association makeAssociation(DTLSTransport trans, AssociationListener li) {
                                        return new ThreadedAssociation(trans, li);
                                    }
                                };
                            } else {
                                _dtls = new DTLSServer(_cert, ds, _al, _ffp) {
                                    @Override
                                    public boolean shouldInitiateAssociation() {
                                        return offerer;
                                    }

                                    @Override
                                    public Association makeAssociation(DTLSTransport trans, AssociationListener li) {
                                        return new ThreadedAssociation(trans, li);
                                    }
                                };
                            }
                        } catch (Exception ex) {
                            Log.debug("DTLS exception");
                            Log.error(ex.toString());
                            ex.printStackTrace();
                        }

                    }
                    break;
                case RUNNING:
                    Log.debug("Ice Running");
                    break;
                case FAILED:
                    Log.debug("Ice Failed");
                    cleanup.run();
                    break;
                case WAITING:
                    Log.debug("Ice Waiting");
                    break;
                case TERMINATED:
                    Log.debug("Ice Terminated");
                    break;

            }
        }
    }

    protected static Agent createAgent(boolean isTrickling) throws IllegalArgumentException, IOException {
        long startTime = System.currentTimeMillis();
        Agent agent = new Agent();
        agent.setTrickling(isTrickling);

        // STUN
        StunCandidateHarvester stunHarv = new StunCandidateHarvester(
                new TransportAddress("146.148.121.175", 3478, Transport.UDP));

        agent.addCandidateHarvester(stunHarv);
        // TURN
        String[] hostnames = new String[]{"146.148.121.175"};
        int port = 3478;
        LongTermCredential longTermCredential
                = new LongTermCredential("device", "bone");

        for (String hostname : hostnames) {
            agent.addCandidateHarvester(
                    new TurnCandidateHarvester(
                            new TransportAddress(hostname, port, Transport.UDP),
                            longTermCredential));
        }

        //UPnP: adding an UPnP harvester because they are generally slow
        //which makes it more convenient to test things like trickle.
        //agent.addCandidateHarvester(new UPNPHarvester());
        long endTime = System.currentTimeMillis();
        long total = endTime - startTime;

        Log.info("Total harvesting time: " + total + "ms.");
        return agent;
    }

    private static IceMediaStream createStream(int rtpPort,
            String streamName,
            Agent agent) throws IllegalArgumentException, IOException {
        IceMediaStream stream = agent.createMediaStream(streamName);

        long startTime = System.currentTimeMillis();

        //TODO: component creation should probably be part of the library. it
        //should also be started after we've defined all components to be
        //created so that we could run the harvesting for everyone of them
        //simultaneously with the others.
        //rtp
        agent.createComponent(
                stream, Transport.UDP, rtpPort, rtpPort, rtpPort + 100);

        long endTime = System.currentTimeMillis();
        Log.info("RTP Component created in "
                + (endTime - startTime) + " ms");
        startTime = endTime;
        /*
         //rtcpComp
         agent.createComponent(
         stream, Transport.UDP, rtpPort + 1, rtpPort + 1, rtpPort + 101);

         endTime = System.currentTimeMillis();
         Log.info("RTCP Component created in "
         + (endTime - startTime) + " ms");
         */
        return stream;
    }

    void addCandidate(String foundation, String component, String protocol, String priority, String ip, String port, String type, String raddr, String rport) {
        IceMediaStream localStream = getStream("data");
        List<Component> localComponents = localStream.getComponents();
        int cid = Integer.parseInt(component);
        boolean rc = false;
        for (Component localComponent : localComponents) {
            int id = localComponent.getComponentID();
            if (cid == id) {
                int iport = Integer.parseInt(port);
                long lpriority = Long.parseLong(priority);
                RemoteCandidate relatedCandidate = null;
                TransportAddress ta = new TransportAddress(ip, iport, Transport.parse(protocol));
                if ((rport != null) && (raddr != null)) {

                    int riport = Integer.parseInt(rport);
                    TransportAddress rta = new TransportAddress(raddr, riport, Transport.parse(protocol));
                    relatedCandidate = localComponent.findRemoteCandidate(rta);

                }
                // localComponent.setDefaultRemoteCandidate(remoteComponent.getDefaultCandidate());
                RemoteCandidate nrc = new RemoteCandidate(
                        ta,
                        localComponent,
                        CandidateType.parse(type),
                        foundation,
                        lpriority,
                        relatedCandidate);
                if (_iceStarted) {
                    Log.debug("adding update remote " + nrc.toString() + " to local " + localComponent.toString());
                    localComponent.addUpdateRemoteCandidates(nrc);
                    localComponent.updateRemoteCandidates();
                } else {
                    Log.debug("adding remote " + nrc.toShortString() + " to local " + localComponent.toShortString());
                    localComponent.addRemoteCandidate(nrc);
                }
                if (protocol.equalsIgnoreCase("udp")) {
                    rc = true;
                }

            } else {
                Log.verb("component Ids dont match " + cid + " != " + id + " ignoring...");
            }
        }
        if (rc) {
            haveRemoteCandy(true);
        }

    }

    IceMediaStream getStream(String target) {
        IceMediaStream ret = null;
        List<IceMediaStream> l = _localAgent.getStreams();
        Log.debug(" count of streams =" + l.size());
        for (IceMediaStream stream : l) {
            if (target.equals(stream.getName())) {
                Log.debug("found " + target);
                ret = stream;
                break;
            } else {
                Log.debug("looking at " + stream.getName());
            }
        }
        return ret;
    }

    public static void main(String[] argv) {
        Log.setLevel(Log.DEBUG);
        try {
            IceConnect ice = new IceConnect(12345);
            ice.buildIce("wbrUTaHy7SNtu20y", "jg/ezzHpJSLGJvUB3N8XwXj9");
            ice.addCandidate("2169522962", "1", "udp", "1509957375", "192.67.4.33", "54321", "host", null, null);
            Log.debug("local ufrag " + ice.getUfrag());
            Log.debug("local pass " + ice.getPass());
            List<Candidate> candies = ice.getCandidates();
            for (Candidate c : candies) {
                Log.debug("candy :" + c.toString());
            }
            ice.startIce();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }

    public void freeAgent() {
        Log.debug("Dumping agent - someone shutdown...");
        if (_localAgent != null) {
            _localAgent.free();
            _localAgent = null;
        }
    }

}
