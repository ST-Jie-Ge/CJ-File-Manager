package com.cj.music.net;
import com.hierynomus.security.bc.BCSecurityProvider;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
/** Known NTLM/HMAC vectors ensure a TLS dependency update does not remove SMB crypto. */
public class SmbCryptoCompatibilityJvmTest {
    private String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte value:bytes)text.append(String.format("%02x",value&255));return text.toString();}
    @Test public void ntlmPasswordDigestRemainsCompatible(){
        com.hierynomus.security.MessageDigest md4=new BCSecurityProvider().getDigest("MD4");
        md4.update("password".getBytes(StandardCharsets.UTF_16LE));assertEquals("8846f7eaee8fb117ad06bdd830b7586c",hex(md4.digest()));
    }
    @Test public void ntlmHmacAndSmbSigningRemainCompatible()throws Exception{
        BCSecurityProvider provider=new BCSecurityProvider();byte[] key=new byte[16];java.util.Arrays.fill(key,(byte)0x0b);
        com.hierynomus.security.Mac md5=provider.getMac("HmacMD5");md5.init(key);md5.update("Hi There".getBytes(StandardCharsets.US_ASCII));assertEquals("9294727a3638bb1c13f48ef8158bfc9d",hex(md5.doFinal()));
        byte[] signingKey=new byte[20];java.util.Arrays.fill(signingKey,(byte)0x0b);
        com.hierynomus.security.Mac sha256=provider.getMac("HmacSHA256");sha256.init(signingKey);sha256.update("Hi There".getBytes(StandardCharsets.US_ASCII));assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",hex(sha256.doFinal()));
        assertNotNull(provider.getCipher("RC4"));assertNotNull(provider.getMac("AESCMAC"));assertNotNull(provider.getAEADBlockCipher("AES/GCM/NoPadding"));
    }
}
