package com.cj.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.cj.music.net.Endpoint;
import org.json.*;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

/** Passwords are encrypted with an Android Keystore key; endpoint metadata carries no credentials. */
final class ConnectionStore {
    static final class Profile {
        final String id,name,root; final Endpoint endpoint;
        final boolean customName;
        Profile(String id,String name,String root,Endpoint endpoint){this(id,name,root,endpoint,name!=null&&!name.isEmpty()&&!name.equals(endpoint.host));}
        Profile(String id,String name,String root,Endpoint endpoint,boolean customName){this.id=id;this.name=name;this.root=normalizeRoot(root,endpoint);this.endpoint=endpoint;this.customName=customName;}
        Profile withPin(String pin){return new Profile(id,name,root,endpoint.withPin(pin),customName);}
        private static String normalizeRoot(String path,Endpoint endpoint){
            if(path==null||path.trim().isEmpty())return endpoint.smb()?"":"/";
            if(endpoint.webdav())return com.cj.music.net.WebdavSession.normalizeRootPath(path);
            return endpoint.smb()?com.cj.music.net.SmbSession.cleanPath(path)
                                :com.cj.music.net.FtpSession.path(path);
        }
    }
    private static final String KEY="filemanager.connection.password.v1";
    private final SharedPreferences prefs;
    ConnectionStore(Context context){prefs=context.getSharedPreferences("connections",Context.MODE_PRIVATE);}
    boolean isSmb(String id){try{return "SMB".equals(new JSONObject(prefs.getString(id,"{}")).optString("protocol"));}catch(JSONException ignored){return false;}}
    /** 证书审批/清除菜单：FTPS 与 WebDAV/OpenList 的 HTTPS 都可能被 pin。 */
    boolean isFtps(String id){try{String protocol=new JSONObject(prefs.getString(id,"{}")).optString("protocol");return protocol.startsWith("FTPS")||protocol.equals("WEBDAV")||protocol.equals("OPENLIST");}catch(JSONException ignored){return false;}}
    /** 音乐播放：FTPS 与 WebDAV/OpenList 暂不支持；FTP/SMB 可播。 */
    boolean musicBlocked(String id){try{String protocol=new JSONObject(prefs.getString(id,"{}")).optString("protocol");return protocol.startsWith("FTPS")||"WEBDAV".equals(protocol)||"OPENLIST".equals(protocol);}catch(JSONException ignored){return false;}}
    String thumbnailScope(String id){try{JSONObject value=new JSONObject(prefs.getString(id,"{}"));return id+"|"+value.optString("host")+"|"+value.optInt("port")+"|"+value.optString("share");}catch(JSONException ignored){return id;}}
    String quickLabel(String id){try{JSONObject value=new JSONObject(prefs.getString(id,"{}"));String host=value.optString("host"),name=value.optString("name"),protocol=value.optString("protocol");
        if(!name.isEmpty()&&value.optBoolean("customName",!name.equals(host)))return name;
        return host+" · "+protocolLabel(protocol);
    }catch(JSONException ignored){return id;}}
    static String protocolLabel(String protocol){
        if(protocol==null)return "";
        if(protocol.startsWith("FTPS"))return "FTPS";
        if("WEBDAV".equals(protocol))return "WebDAV";
        if("OPENLIST".equals(protocol))return "OpenList";
        return protocol;
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(KEY)){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
        return (SecretKey)store.getKey(KEY,null);
    }
    synchronized void save(Profile p) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(p.id.getBytes(StandardCharsets.UTF_8));
        JSONObject o=new JSONObject();o.put("id",p.id);o.put("name",p.name);o.put("root",p.root);o.put("host",p.endpoint.host);o.put("port",p.endpoint.port);o.put("protocol",p.endpoint.protocol.name());o.put("username",p.endpoint.username);o.put("charset",p.endpoint.charset);o.put("pin",p.endpoint.certificatePin);o.put("domain",p.endpoint.domain);o.put("share",p.endpoint.share);o.put("plainHttp",p.endpoint.plainHttp);
        o.put("customName",p.customName);o.put("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP));o.put("password",Base64.encodeToString(c.doFinal(p.endpoint.password.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
        if(!prefs.edit().putString(p.id,o.toString()).commit())throw new java.io.IOException("无法保存连接");
    }
    synchronized Profile get(String id) throws Exception {
        String value=prefs.getString(id,null);if(value==null)throw new java.io.IOException("连接配置不存在，请重新添加");JSONObject o=new JSONObject(value);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(o.getString("iv"),Base64.NO_WRAP)));c.updateAAD(id.getBytes(StandardCharsets.UTF_8));
        String password=new String(c.doFinal(Base64.decode(o.getString("password"),Base64.NO_WRAP)),StandardCharsets.UTF_8);
        String username=o.getString("username");
        if("SMB".equals(o.getString("protocol"))&&"anonymous".equals(username)&&"anonymous@".equals(password)){username="";password="";}
        return new Profile(id,o.getString("name"),o.getString("root"),new Endpoint(o.getString("host"),o.getInt("port"),Endpoint.Protocol.valueOf(o.getString("protocol")),username,password,o.getString("charset"),o.optString("pin"),o.optString("domain",""),o.optString("share",""),o.optBoolean("plainHttp",false)),o.optBoolean("customName",!o.getString("name").equals(o.getString("host"))));
    }
    synchronized LinkedHashMap<String,String> names(){LinkedHashMap<String,String> result=new LinkedHashMap<>();for(Map.Entry<String,?> e:prefs.getAll().entrySet())try{result.put(e.getKey(),new JSONObject(String.valueOf(e.getValue())).getString("name"));}catch(JSONException ignored){}return result;}
    synchronized void remove(String id){prefs.edit().remove(id).apply();}
}
