package com.cj.music.net;

import org.junit.Test;
import java.io.*;
import java.lang.reflect.Field;
import java.security.KeyStore;
import javax.net.ssl.*;
import static org.junit.Assert.*;

public final class TlsIdentityTest {
    private X509TrustManager trustedFixtureManager() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(System.getProperty("test.dns.keystore"))) {
            keys.load(in, "test-only".toCharArray());
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keys);
        for (TrustManager manager : factory.getTrustManagers())
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        throw new AssertionError("no trust manager");
    }
    private void trustFixture(FtpSession client) throws Exception {
        Field field = FtpSession.class.getDeclaredField("trust"); field.setAccessible(true);
        CertificateTrust trust = (CertificateTrust) field.get(client);
        Field system = CertificateTrust.class.getDeclaredField("system"); system.setAccessible(true);
        system.set(trust, trustedFixtureManager());
    }
    @Test public void wrongHostnameRejectedBeforeCredentials() throws Exception {
        CertificateTrust trust = new CertificateTrust("");
        Field system = CertificateTrust.class.getDeclaredField("system"); system.setAccessible(true);
        system.set(trust, trustedFixtureManager());
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{trust}, null);
        try (FtpSessionTest.Fixture server = new FtpSessionTest.Fixture(true,System.getProperty("test.dns.keystore"));
             java.net.Socket tcp = new java.net.Socket("127.0.0.1",server.port());
             SSLSocket socket = (SSLSocket)context.getSocketFactory().createSocket(tcp,"wrong.example",server.port(),true)) {
            SSLParameters parameters=socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);
            try {socket.startHandshake();fail("Accepted a trusted certificate for another hostname");}
            catch(SSLException expected) { }
            assertFalse(server.commands.stream().anyMatch(s->s.startsWith("USER")));
        }
    }
    @Test public void wrongIpRejectedBeforeCredentialsExplicit()throws Exception {wrongIp(false);}
    @Test public void wrongIpRejectedBeforeCredentialsImplicit()throws Exception {wrongIp(true);}
    private void wrongIp(boolean implicit)throws Exception {
        try(FtpSessionTest.Fixture server=new FtpSessionTest.Fixture(implicit,System.getProperty("test.dns.keystore"));
            FtpSession client=new FtpSession(new Endpoint("127.0.0.1",server.port(),
                implicit?Endpoint.Protocol.FTPS_IMPLICIT:Endpoint.Protocol.FTPS_EXPLICIT,"test","test","UTF-8",""))) {
            trustFixture(client);
            try{client.connect();fail("DNS-only certificate must not authenticate an IP address");}
            catch(IOException expected){assertFalse("Hostname mismatch must not offer manual approval",expected instanceof CertificateApprovalException);}
            assertFalse(server.commands.stream().anyMatch(s->s.startsWith("USER")));
        }
    }
    @Test public void trustedExplicitTlsRoundTrip() throws Exception { trustedRoundTrip(false); }
    @Test public void trustedImplicitTlsRoundTrip() throws Exception { trustedRoundTrip(true); }
    private void trustedRoundTrip(boolean implicit) throws Exception {
        try (FtpSessionTest.Fixture server = new FtpSessionTest.Fixture(implicit,System.getProperty("test.dns.keystore"));
             FtpSession client = new FtpSession(new Endpoint("localhost", server.port(),
                 implicit ? Endpoint.Protocol.FTPS_IMPLICIT : Endpoint.Protocol.FTPS_EXPLICIT,
                 "test", "test", "UTF-8", ""))) {
            trustFixture(client); client.connect();
            client.upload("/", "identity.bin", new ByteArrayInputStream(new byte[]{1,2,3}), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            client.download("/identity.bin", out, null);
            assertArrayEquals(new byte[]{1,2,3}, out.toByteArray());
        }
    }
}
