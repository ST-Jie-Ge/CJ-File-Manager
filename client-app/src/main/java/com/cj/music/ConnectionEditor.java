package com.cj.music;

import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.cj.music.net.Endpoint;
import com.cj.music.resources.R;
import java.util.UUID;

/**
 * 侧边栏内嵌的连接表单（不用对话框）：填完点“保存并连接”即写入配置并连接。
 *
 * SMB 只露出「主机 / 端口 / 登录方式」，域·工作组、共享名·路径折叠在「更多配置」里。
 * WebDAV/OpenList 复用同一骨架，起始目录即 URL 路径前缀（全部平铺，不折叠）。
 * 所有输入边填边写进草稿（SharedPreferences），连接失败重开表单自动回填，保存成功后清除。
 */
final class ConnectionEditor {
    interface Connect { void run(ConnectionStore.Profile profile); }

    private static final String[] DRAFT_KEYS = {
            "name","tls","host","port","anonymous","smbLogin","user","password","domain","path","charset"
    };

    static View page(MainActivity activity,ConnectionStore.Profile original,Connect connect,Runnable onDone) {
        return page(activity,original,connect,onDone,original==null?Endpoint.Protocol.FTP:original.endpoint.protocol);
    }

    static View page(MainActivity activity,ConnectionStore.Profile original,Connect connect,Runnable onDone,Endpoint.Protocol requested) {
        final Endpoint.Protocol initial=original==null?requested:original.endpoint.protocol;
        final boolean tlsFamily=initial==Endpoint.Protocol.FTPS_EXPLICIT||initial==Endpoint.Protocol.FTPS_IMPLICIT;
        final boolean smbForm=initial==Endpoint.Protocol.SMB;
        final boolean webdavForm=initial==Endpoint.Protocol.WEBDAV||initial==Endpoint.Protocol.OPENLIST;
        final String family=smbForm?"SMB":webdavForm?(initial==Endpoint.Protocol.OPENLIST?"OpenList":"WebDAV"):tlsFamily?"FTPS":"FTP";
        final String draftKey="conn_draft_"+(original==null?family:original.id);

        // 草稿：编辑已有连接时不干扰配置（只用于新建表单的断点续填）。
        final android.content.SharedPreferences drafts=
                activity.getSharedPreferences("connection_drafts",android.content.Context.MODE_PRIVATE);
        final boolean useDraft=original==null;
        final java.util.function.BiFunction<String,String,String> draft=(key,fallback)->
                useDraft?drafts.getString(draftKey+"|"+key,fallback):fallback;

        LinearLayout fields=new LinearLayout(activity);fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(activity,12),dp(activity,4),dp(activity,12),dp(activity,24));

        EditText name=input(activity,"连接名称",draft.apply("name",original==null||!original.customName?"":original.name));
        fields.addView(label(activity,"连接名称"));fields.addView(name);

        Spinner tls=new Spinner(activity);
        tls.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"显式 TLS","隐式 TLS"}));
        tls.setSelection(draft.apply("tls",initial==Endpoint.Protocol.FTPS_IMPLICIT?"1":"0").equals("1")?1:0);
        tls.setBackgroundResource(R.drawable.client_page_row);tls.setPadding(dp(activity,12),0,dp(activity,12),0);
        if(tlsFamily){fields.addView(label(activity,"TLS 模式"));fields.addView(tls,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,48)));}

        EditText host=input(activity,webdavForm?"例如 192.168.1.10 或 http://192.168.1.10":"例如 192.168.1.10","");
        host.setText(draft.apply("host",original==null?"":original.endpoint.displayHost()));
        fields.addView(label(activity,"服务器地址"));fields.addView(host);

        String defaultPort=smbForm?"445"
                :webdavForm?""
                :initial==Endpoint.Protocol.FTPS_IMPLICIT?"990":"21";
        EditText port=input(activity,"","");
        port.setText(draft.apply("port",original==null?defaultPort:Integer.toString(original.endpoint.port)));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        fields.addView(label(activity,"端口"));fields.addView(port);
        if(webdavForm)fields.addView(hint(activity,"端口留空 = 跟随协议默认（https 443 / http 80）"));
        if(webdavForm){
            /* 改地址前缀时把「另一个默认端口」顺手换过来：443↔80。留空的不动（留空本身就有意义）。 */
            host.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int a,int b,int c){}
                public void onTextChanged(CharSequence s,int a,int b,int c){}
                public void afterTextChanged(Editable e){
                    boolean http=host.getText().toString().trim().toLowerCase(java.util.Locale.ROOT).startsWith("http://");
                    String current=port.getText().toString().trim();
                    if(http&&current.equals("443"))port.setText("80");
                    else if(!http&&current.equals("80"))port.setText("443");
                }});
        }

        // 登录方式与账户密码在同一块里，随登录方式显隐账户密码（SMB 默认来宾，无需填账户）。
        LinearLayout loginBlock=new LinearLayout(activity);loginBlock.setOrientation(LinearLayout.VERTICAL);
        CheckBox anonymous=new CheckBox(activity);anonymous.setText("匿名登录");anonymous.setPadding(dp(activity,4),dp(activity,10),0,0);
        TextView loginLabel=label(activity,"登录方式");
        Spinner smbLogin=new Spinner(activity);
        smbLogin.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"账号密码","来宾（Guest）","匿名（空身份）"}));
        smbLogin.setBackgroundResource(R.drawable.client_page_row);
        TextView userLabel=label(activity,"账户"),passwordLabel=label(activity,"密码");
        EditText user=input(activity,"","");
        user.setText(draft.apply("user",original==null?"":original.endpoint.username));
        EditText password=input(activity,"","");
        password.setText(draft.apply("password",original==null?"":original.endpoint.password));
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);

        final EditText domain=input(activity,"可留空（企业域/工作组）","");
        domain.setText(draft.apply("domain",original==null?"":original.endpoint.domain));
        TextView domainLabel=label(activity,"域 / 工作组");

        final EditText path=input(activity,"/","");
        String originalPath=original==null?"":(original.endpoint.smb()
                ?(original.endpoint.share+(original.root.isEmpty()?"":"/"+original.root))
                :original.endpoint.webdav()?original.endpoint.share:original.root);
        path.setText(draft.apply("path",original==null?(smbForm?"":"/"):originalPath));
        final TextView pathLabel=label(activity,"起始目录");

        Spinner charset=new Spinner(activity);
        charset.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"UTF-8","GBK","ISO-8859-1"}));
        charset.setBackgroundResource(R.drawable.client_page_row);charset.setPadding(dp(activity,12),0,dp(activity,12),0);
        TextView charsetLabel=label(activity,"文件名编码");
        String draftCharset=draft.apply("charset",original==null?"UTF-8":original.endpoint.charset);
        charset.setSelection("GBK".equals(draftCharset)?1:"ISO-8859-1".equals(draftCharset)?2:0);

        // —— 主区：名称 / 服务器地址 / 端口 / 登录方式+账户密码（账户密码随登录方式显隐） ——
        if(!webdavForm){loginBlock.addView(anonymous);loginBlock.addView(loginLabel);loginBlock.addView(smbLogin);}
        loginBlock.addView(userLabel);loginBlock.addView(user);
        loginBlock.addView(passwordLabel);loginBlock.addView(password);
        fields.addView(loginBlock);

        // —— 更多配置折叠区（WebDAV/OpenList 例外：内容少，直接平铺不折叠） ——
        final LinearLayout more=new LinearLayout(activity);more.setOrientation(LinearLayout.VERTICAL);
        more.setVisibility(webdavForm?View.VISIBLE:View.GONE);
        Runnable updateLogin=()->{boolean smb=smbForm;
            anonymous.setVisibility(smb?View.GONE:View.VISIBLE);
            loginLabel.setVisibility(smb?View.VISIBLE:View.GONE);
            smbLogin.setVisibility(smb?View.VISIBLE:View.GONE);
            boolean automatic=smb?smbLogin.getSelectedItemPosition()!=0:anonymous.isChecked();
            // 来宾/匿名：不需要填账户密码，连标签一起隐藏。
            int vis=automatic?View.GONE:View.VISIBLE;
            userLabel.setVisibility(vis);user.setVisibility(vis);
            passwordLabel.setVisibility(vis);password.setVisibility(vis);};
        anonymous.setOnCheckedChangeListener((v,checked)->updateLogin.run());
        anonymous.setChecked(draft.apply("anonymous",webdavForm?"1":"0").equals("1")
                ||(original!=null&&!useDraft&&!original.endpoint.smb()&&original.endpoint.username.equals("anonymous")));
        // SMB 新建默认「来宾（Guest）」：免填账户密码，更符合 NAS 局域网习惯。
        smbLogin.setSelection(Integer.parseInt(draft.apply("smbLogin",original!=null&&original.endpoint.smb()
                ?(original.endpoint.username.isEmpty()?"2":original.endpoint.username.equalsIgnoreCase("Guest")&&original.endpoint.password.isEmpty()?"1":"0")
                :smbForm?"1":"0")));
        smbLogin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){updateLogin.run();}});

        // 登录方式/账户密码在主区 loginBlock；折叠区只剩共享路径、域、编码。
        more.addView(domainLabel);more.addView(domain);
        more.addView(pathLabel);more.addView(path);
        if(!smbForm){more.addView(charsetLabel);more.addView(charset,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,48)));}

        TextView moreToggle=hint(activity,"更多配置 ▾");
        moreToggle.setClickable(true);moreToggle.setFocusable(true);
        moreToggle.setOnClickListener(v->{boolean open=more.getVisibility()==View.GONE;
            more.setVisibility(open?View.VISIBLE:View.GONE);
            moreToggle.setText(open?"更多配置 ▴":"更多配置 ▾");});
        if(webdavForm)moreToggle.setVisibility(View.GONE);
        fields.addView(moreToggle);fields.addView(more);

        if(smbForm){pathLabel.setText("共享名 / 路径（可留空）");path.setHint("留空列出共享目录");}
        else if(webdavForm){pathLabel.setText("起始目录（可留空）");path.setHint("例如 /dav 或 /照片");}
        domain.setEnabled(smbForm);
        boolean showDomain=smbForm;
        domain.setVisibility(showDomain?View.VISIBLE:View.GONE);
        domainLabel.setVisibility(showDomain?View.VISIBLE:View.GONE);

        updateLogin.run();
        tls.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(tlsFamily){String old=port.getText().toString();if(old.equals("21")||old.equals("990"))port.setText(pos==1?"990":"21");}}});

        final TextView detail=hint(activity,"");
        detail.setVisibility(View.GONE);fields.addView(detail);

        final TextView submit=new TextView(activity);submit.setText("保存并连接 "+family);submit.setTextSize(14);submit.setGravity(android.view.Gravity.CENTER);submit.setClickable(true);submit.setFocusable(true);
        TypedValue accent=new TypedValue();activity.getTheme().resolveAttribute(R.attr.client_accent,accent,true);submit.setTextColor(accent.data);
        submit.setBackgroundResource(R.drawable.client_page_row);
        LinearLayout.LayoutParams submitParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,46));submitParams.topMargin=dp(activity,16);submit.setLayoutParams(submitParams);
        fields.addView(submit);

        // —— 草稿：任何字段一变就落盘，连接失败重开表单即可续填 ——
        if(useDraft){
            final Runnable persist=()->{android.content.SharedPreferences.Editor editor=drafts.edit();
                editor.putString(draftKey+"|name",name.getText().toString())
                        .putString(draftKey+"|tls",tls.getSelectedItemPosition()==1?"1":"0")
                        .putString(draftKey+"|host",host.getText().toString())
                        .putString(draftKey+"|port",port.getText().toString())
                        .putString(draftKey+"|anonymous",anonymous.isChecked()?"1":"0")
                        .putString(draftKey+"|smbLogin",Integer.toString(smbLogin.getSelectedItemPosition()))
                        .putString(draftKey+"|user",user.getText().toString())
                        .putString(draftKey+"|password",password.getText().toString())
                        .putString(draftKey+"|domain",domain.getText().toString())
                        .putString(draftKey+"|path",path.getText().toString())
                        .putString(draftKey+"|charset",charset.getSelectedItem().toString());
                editor.apply();};
            anonymous.setOnCheckedChangeListener((v,checked)->{updateLogin.run();persist.run();});
            TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable e){persist.run();}};
            for(EditText field:new EditText[]{name,host,port,user,password,domain,path})field.addTextChangedListener(watcher);
            AdapterView.OnItemSelectedListener persistSpin=new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){updateLogin.run();persist.run();}};
            tls.setOnItemSelectedListener(persistSpin);smbLogin.setOnItemSelectedListener(persistSpin);charset.setOnItemSelectedListener(persistSpin);
        }

        submit.setOnClickListener(v->{try{
            Endpoint.Protocol type=tlsFamily?(tls.getSelectedItemPosition()==1?Endpoint.Protocol.FTPS_IMPLICIT:Endpoint.Protocol.FTPS_EXPLICIT):initial;
            boolean smb=type==Endpoint.Protocol.SMB;
            boolean webdav=type==Endpoint.Protocol.WEBDAV||type==Endpoint.Protocol.OPENLIST;
            /* 地址栏前缀自动判定 scheme：http:// → 明文；https:// → TLS；不写 → 默认 TLS。
               前缀只在这里剥，Endpoint 拿到的仍是纯主机名（+plainHttp 标记）。 */
            String h=host.getText().toString().trim(),bareHost=h;
            boolean plainHttp=false;
            if(webdav){
                String lower=h.toLowerCase(java.util.Locale.ROOT);
                if(lower.startsWith("http://")){plainHttp=true;bareHost=h.substring(7).trim();}
                else if(lower.startsWith("https://")){bareHost=h.substring(8).trim();}
                while(bareHost.endsWith("/"))bareHost=bareHost.substring(0,bareHost.length()-1).trim();
            }
            if(bareHost.isEmpty())throw new IllegalArgumentException("请填写服务器地址");
            String portText=port.getText().toString().trim();
            int p;
            if(portText.isEmpty()){
                if(!webdav)throw new IllegalArgumentException("请填写端口");
                p=plainHttp?80:443; // 端口留空跟随 scheme 默认：明文 80、TLS 443
            }else{
                p=Integer.parseInt(portText);
            }
            String pin=original!=null&&original.endpoint.host.equals(bareHost)&&original.endpoint.port==p&&original.endpoint.protocol==type&&original.endpoint.plainHttp==plainHttp?original.endpoint.certificatePin:"";
            String share="",root=path.getText().toString().trim();
            if(type==Endpoint.Protocol.SMB){
                // SMB 起始目录形如 share 或 share/dir：第一段是共享名，其余是共享内路径。
                // 去掉前导斜杠（用户可能残留 FTP 的 / 或习惯性输 /share），避免解析出空共享名；
                // 分隔符兼容 / 与 \（Windows 习惯）。
                String smbPath=root.replace('\\','/');while(smbPath.startsWith("/"))smbPath=smbPath.substring(1);
                int slash=smbPath.indexOf('/');if(slash<0){share=smbPath;root="";}else{share=smbPath.substring(0,slash);root=smbPath.substring(slash+1);}
            }else if(type==Endpoint.Protocol.WEBDAV||type==Endpoint.Protocol.OPENLIST){
                // WebDAV/OpenList：起始目录即 URL 路径前缀，规范化后放进 share（与 SMB 同字段承载）。
                share=com.cj.music.net.WebdavSession.normalizeRootPath(root);
                root="";
            }
            int login=smb?smbLogin.getSelectedItemPosition():0;
            String loginUser=smb?(login==2?"":login==1?"Guest":user.getText().toString().trim())
                    :anonymous.isChecked()?"anonymous":user.getText().toString().trim();
            String loginPassword=smb?(login==0?password.getText().toString():"")
                    :anonymous.isChecked()?"anonymous@":password.getText().toString();
            if(loginUser.isEmpty()&&(!smb||login!=2))throw new IllegalArgumentException("请填写用户名，或选择免密码登录方式");
            if(smb&&login==0&&loginUser.isEmpty())throw new IllegalArgumentException("请填写账户，或改用来宾/匿名登录");
            Endpoint endpoint=new Endpoint(bareHost,p,type,loginUser,loginPassword,smb?"UTF-8":charset.getSelectedItem().toString(),pin,smb?domain.getText().toString():"",share,plainHttp);
            String n=name.getText().toString().trim();
            ConnectionStore.Profile profile=new ConnectionStore.Profile(original==null?UUID.randomUUID().toString():original.id,n.isEmpty()?bareHost:n,root,endpoint,!n.isEmpty());
            if(onDone!=null)onDone.run();connect.run(profile);
        }catch(Exception failure){boolean night=(activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
            detail.setVisibility(View.VISIBLE);detail.setTextColor(night?0xFFF2B8B5:0xFFB3261E);
            detail.setText("请检查输入："+(failure.getMessage()==null?"格式不正确":failure.getMessage()));}});

        ScrollView scroll=new ScrollView(activity);scroll.setClipToPadding(false);scroll.addView(fields,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }
    private static EditText input(MainActivity activity,String hint,String value){EditText input=new EditText(activity);input.setSingleLine(true);input.setText(value==null?"":value);input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setBackgroundResource(R.drawable.client_search_field);
        input.setPadding(dp(activity,14),0,dp(activity,14),0);input.setTextSize(14);
        input.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,48)));
        TypedValue color=new TypedValue();activity.getTheme().resolveAttribute(R.attr.client_text,color,true);input.setTextColor(color.data);
        TypedValue secondary=new TypedValue();activity.getTheme().resolveAttribute(R.attr.client_text_secondary,secondary,true);input.setHintTextColor(secondary.data);
        return input;}
    private static TextView label(MainActivity activity,String text){TextView view=new TextView(activity);view.setText(text);view.setTextSize(12);
        view.setPadding(dp(activity,4),dp(activity,12),0,dp(activity,4));TypedValue color=new TypedValue();activity.getTheme().resolveAttribute(R.attr.client_text_secondary,color,true);view.setTextColor(color.data);return view;}
    private static TextView hint(MainActivity activity,String text){TextView view=new TextView(activity);view.setText(text);view.setTextSize(12);view.setLineSpacing(0,1.25f);
        view.setPadding(dp(activity,4),dp(activity,10),0,0);TypedValue color=new TypedValue();activity.getTheme().resolveAttribute(R.attr.client_text_secondary,color,true);view.setTextColor(color.data);return view;}
    private static int dp(MainActivity activity,int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
