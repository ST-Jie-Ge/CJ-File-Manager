package com.cj.music.net;

import java.net.Socket;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

/** Retain the handshake context: Android's basic trust-manager overload does not check hostnames. */
final class CertificateTrust extends X509ExtendedTrustManager {
    final X509TrustManager system;
    final String pin;
    volatile X509Certificate offered;
    volatile boolean rejected;
    volatile String sessionCertificate;
    CertificateTrust(String pin) throws GeneralSecurityException {
        this.pin=pin;
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore)null);
        X509TrustManager found=null;
        for(TrustManager manager:factory.getTrustManagers()) if(manager instanceof X509TrustManager) found=(X509TrustManager)manager;
        if(found==null) throw new GeneralSecurityException("系统证书验证器不可用");
        system=found;
    }
    private boolean checkPin(X509Certificate[] chain) throws CertificateException {
        if(chain==null||chain.length==0) throw new CertificateException("服务器没有提供证书");
        offered=chain[0];
        offered.checkValidity();
        String actual=fingerprint(offered);
        if(sessionCertificate!=null&&!sessionCertificate.equals(actual)) throw new CertificateException("数据连接证书与控制连接不一致");
        if(!pin.isEmpty()&&!pin.equals(actual)) throw new CertificateException("服务器证书已变化；请编辑连接并重新核对指纹");
        return !pin.isEmpty();
    }
    private X509ExtendedTrustManager checkChain(X509Certificate[] chain,String auth) throws CertificateException {
        // Only chain rejection can request manual approval. Hostname failures must not do so.
        try { system.checkServerTrusted(chain,auth); }
        catch(CertificateException failure) { rejected=true; throw failure; }
        if(!(system instanceof X509ExtendedTrustManager)) throw new CertificateException("系统不支持带主机名的证书验证");
        return (X509ExtendedTrustManager)system;
    }
    @Override public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
        if(!checkPin(chain)) throw new CertificateException("证书验证缺少 TLS 主机名上下文");
    }
    @Override public void checkServerTrusted(X509Certificate[] chain,String auth,Socket socket) throws CertificateException {
        if(checkPin(chain)) return;
        if(!(socket instanceof SSLSocket)||((SSLSocket)socket).getHandshakeSession()==null
                ||!"HTTPS".equalsIgnoreCase(((SSLSocket)socket).getSSLParameters().getEndpointIdentificationAlgorithm()))
            throw new CertificateException("证书验证缺少 TLS 主机名上下文");
        checkChain(chain,auth).checkServerTrusted(chain,auth,socket);
    }
    @Override public void checkServerTrusted(X509Certificate[] chain,String auth,SSLEngine engine) throws CertificateException {
        if(checkPin(chain)) return;
        if(engine==null||engine.getHandshakeSession()==null
                ||!"HTTPS".equalsIgnoreCase(engine.getSSLParameters().getEndpointIdentificationAlgorithm()))
            throw new CertificateException("证书验证缺少 TLS 主机名上下文");
        checkChain(chain,auth).checkServerTrusted(chain,auth,engine);
    }
    @Override public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException {system.checkClientTrusted(c,a);}
    @Override public void checkClientTrusted(X509Certificate[] c,String a,Socket socket)throws CertificateException {
        if(system instanceof X509ExtendedTrustManager)((X509ExtendedTrustManager)system).checkClientTrusted(c,a,socket);
        else system.checkClientTrusted(c,a);
    }
    @Override public void checkClientTrusted(X509Certificate[] c,String a,SSLEngine engine)throws CertificateException {
        if(system instanceof X509ExtendedTrustManager)((X509ExtendedTrustManager)system).checkClientTrusted(c,a,engine);
        else system.checkClientTrusted(c,a);
    }
    @Override public X509Certificate[] getAcceptedIssuers(){return system.getAcceptedIssuers();}
    static String fingerprint(X509Certificate certificate) {
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(java.util.Locale.ROOT,"%02X",b&255));return s.toString();}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
}
