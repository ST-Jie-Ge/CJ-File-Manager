package com.cj.music.net;

import java.io.IOException;
import java.security.cert.X509Certificate;

/** No credentials have been sent when first-use certificate validation raises this error. */
public final class CertificateApprovalException extends IOException {
    public final String fingerprint, subject, expires;
    CertificateApprovalException(X509Certificate certificate, Throwable cause) {
        super("无法验证服务器证书，请核对 SHA-256 指纹后决定是否信任", cause);
        fingerprint=CertificateTrust.fingerprint(certificate);
        subject=certificate.getSubjectX500Principal().getName();
        expires=certificate.getNotAfter().toString();
    }
}
