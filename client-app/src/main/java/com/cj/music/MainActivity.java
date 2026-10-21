package com.cj.music;

import android.app.Activity;
import android.content.*;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.*;
import android.text.format.Formatter;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.slider.Slider;
import com.cj.music.resources.R;
import org.json.*;
import com.cj.music.net.*;
import androidx.core.content.FileProvider;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.*;
import java.text.Collator;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Local SAF and FTP/FTPS browser; browsing and transfer sessions are independent. */
public class MainActivity extends AppCompatActivity {
    private static final int PICK_BACKGROUND=11, REQUEST_STORAGE=12, EDIT_IMAGE=13;
    private final ExecutorService browseIo=Executors.newSingleThreadExecutor();
    private final ExecutorService transferIo=Executors.newSingleThreadExecutor();
    private final RemoteSessions remoteSessions=new RemoteSessions();
    private ConnectionStore connections;
    private volatile RemoteSession transferSession;
    private final AtomicBoolean transferCancelled=new AtomicBoolean();
    private boolean transferring;
    private AlertDialog archiveDialog; private volatile boolean archiveCancelled;
    /* 打开目录/网络位置时的转圈弹窗：先切标签页，500ms 还没好才弹——慢目录、慢网络不再把界面卡住。 */
    private AlertDialog loadDialog; private Runnable loadDialogTask; private int loadDialogTicket;
    /* 下拉/上拉刷新：指示器、拖动状态，以及「结果至少等 1 秒」的时间闸门。
       listGateUntil 是这次刷新允许套用列表结果的最早时刻——数据再快也不立刻换列表。 */
    private View refreshPillTop,refreshPillBottom;private boolean pulling,refreshing;private int pullDirection;private float pullDistance,pullBase;
    private long listGateUntil;
    /** 当前 entries 对应的目录键（本地/远程各带前缀，避免远程 "/" 与本地 "/" 撞车），用于判断「同目录重载」。 */
    private String loadedKey="";
    private static final int PULL_TRIGGER_DP=72;
    private String clipboardProfile="";
    private long progressTime;

    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ArrayList<TabState> tabs=new ArrayList<>();
    private final ArrayList<Entry> entries=new ArrayList<>();
    private final Set<String> selected=new HashSet<>();
    private final ArrayList<Entry> clipboard=new ArrayList<>();
    private DrawerLayout drawer; private TabLayout tabBar; private RecyclerView list;
    private LinearLayout actions; private EditText search; private TextView path;
    private NavigationView sidebarNav; private LinearLayout sidebarPage; private FrameLayout sidebarPageBody; private TextView sidebarPageTitle;
    private View sidebarHeader;
    private MusicControls musicControls;
    private final Handler musicUi=new Handler(Looper.getMainLooper());
    private final Runnable musicTick=new Runnable(){public void run(){if(isDestroyed())return;if(musicControls!=null)musicControls.update();musicUi.postDelayed(this,500);}};
    private LinearLayout bottomBar; private View contextHolder;
    private LinearLayout archivePanel; private LinearLayout archiveRow1,archiveRow2; private TextView archiveTitle;
    /** 压缩包面板状态：archiveShown=当前是否展开（驱动高度动画），archiveUri=上一次填充的文件（避免重复重建）。 */
    private boolean archiveShown; private String archiveUri=""; private android.animation.ValueAnimator archiveAnim;
    private String archivePassword=""; private java.nio.charset.Charset archiveCharset;
    private String pendingArchiveFile;
    private View favoriteButton; private ImageView favoriteIcon; private int lastBarMode=-1;
    private TextView taskText; private TextView appearanceDim,appearanceAlpha; private String currentPage="";
    /** openPage 自己打开抽屉时为 true，让 onDrawerOpened 放过这一次，不要把它当成“用户想回主界面”。 */
    private boolean pendingPageOpen;
    private SharedPreferences prefs; private int active=0, generation=0; private boolean rebuilding, moveClipboard, busy;
    private long lastBackTime;
    private String clipboardSource="", lastTask="暂无传输任务";
    private final FileAdapter adapter=new FileAdapter();
    private final ExecutorService thumbIo=Executors.newFixedThreadPool(2);
    private final Set<RemoteSession> thumbnailSessions=ConcurrentHashMap.newKeySet();
    private final Set<String> pendingThumbnails=ConcurrentHashMap.newKeySet();
    private final java.util.Map<String,ArrayList<java.lang.ref.WeakReference<ImageView>>> thumbnailObservers=new java.util.HashMap<>();
    private final java.util.Map<String,Long> unavailableThumbnails=new ConcurrentHashMap<>();
    private volatile boolean thumbnailsClosed;
    private final android.util.LruCache<String,android.graphics.Bitmap> thumbCache=new android.util.LruCache<String,android.graphics.Bitmap>((int)(Runtime.getRuntime().maxMemory()/8)){@Override protected int sizeOf(String key,android.graphics.Bitmap b){return b.getByteCount();}};
    private static class Place { String uri,name; Place(String u,String n){uri=u;name=n;} }
    private static class TabState {
        String tree="", label="新标签", profileId=""; ArrayList<Place> stack=new ArrayList<>(); int sort=0,scroll=0; boolean desc=false,folders=true; String query="";
        String current(){return stack.isEmpty()?"":stack.get(stack.size()-1).uri;}
        String rootTitle(){return stack.isEmpty()?label:stack.get(0).name;}
        String title(){return stack.isEmpty()?label:stack.get(stack.size()-1).name;}
        boolean remote(){return !profileId.isEmpty();}
    }
    private static class Entry {String uri,name,mime,profileId="";long size,time,flags;boolean dir;}
    private TabState tab(){return tabs.get(active);}
    /** 取当前标签的容错版本：列表重绘由数据变更回调驱动，取不到就按「不过滤」处理，绝不在这里抛异常——
        一旦抛出，notifyDataSetChanged 之后的重绘会整个断掉，界面就和真实目录对不上了。 */
    private TabState activeTab(){return tabs.isEmpty()?null:(active>=0&&active<tabs.size()?tabs.get(active):null);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private int attrColor(int attr){android.util.TypedValue tv=new android.util.TypedValue();getTheme().resolveAttribute(attr,tv,true);return tv.data;}
    private String fullPath(TabState t){if(t.stack.isEmpty())return t.label;StringBuilder b=new StringBuilder();for(Place p:t.stack){if(b.length()>0)b.append(" / ");b.append(p.name);}return b.toString();}
    /** 标签左侧图标：ROM / TF 卡 / 网络位置各不相同。 */
    private int tabIcon(TabState t){if("PHOTO_FAVORITES".equals(t.tree))return R.drawable.client_bottom_icon_favorite_filled;
        if(t.remote())return R.drawable.client_ic_network;
        String root=t.stack.isEmpty()?"":t.stack.get(0).uri;
        if(root.startsWith("/mnt/media_rw")||root.startsWith("/mnt/sdcard"))return R.drawable.client_ic_sd_card;
        if(root.startsWith("/storage/")&&!root.startsWith("/storage/emulated")&&!root.startsWith("/storage/self"))return R.drawable.client_ic_sd_card;
        return R.drawable.client_ic_phone;
    }
    @Override public void onCreate(Bundle state){
        prefs=getSharedPreferences("client",MODE_PRIVATE);connections=new ConnectionStore(this);
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(state);
        setContentView(R.layout.client_browser_shell);
        ClientWindowInsets.apply(this);
        android.util.TypedValue surface=new android.util.TypedValue();getTheme().resolveAttribute(R.attr.client_surface,surface,true);
        getWindow().setNavigationBarColor(surface.data);
        boolean light=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)!=android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat bars=new androidx.core.view.WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView());bars.setAppearanceLightStatusBars(light);bars.setAppearanceLightNavigationBars(light);
        drawer=findViewById(R.id.client_drawer);tabBar=findViewById(R.id.client_tabs);list=findViewById(R.id.client_files);
        tabBar.setClipChildren(false);tabBar.setClipToPadding(false);((ViewGroup)tabBar.getParent()).setClipChildren(false);
        search=findViewById(R.id.client_search_input);path=findViewById(R.id.client_breadcrumb);
        list.setAdapter(adapter);
        list.addItemDecoration(new androidx.recyclerview.widget.RecyclerView.ItemDecoration(){
            @Override public void getItemOffsets(android.graphics.Rect out,View view,androidx.recyclerview.widget.RecyclerView parent,androidx.recyclerview.widget.RecyclerView.State state){
                out.set(0,0,0,0);if(gridMode()||!(parent.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager))return;
                int spans=((androidx.recyclerview.widget.GridLayoutManager)parent.getLayoutManager()).getSpanCount();
                int position=parent.getChildAdapterPosition(view);if(position==androidx.recyclerview.widget.RecyclerView.NO_POSITION||spans<2)return;
                // Both gutters use physical pixels, independent of density.
                out.top=position>=spans?3:0;
                int column=position%spans,gap=12,start=column*gap/spans,end=gap-(column+1)*gap/spans;
                if(parent.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL){out.left=end;out.right=start;}else{out.left=start;out.right=end;}
            }
        });applyViewMode();
        bottomBar=findViewById(R.id.client_bottom_bar);contextHolder=findViewById(R.id.client_context_actions);
        archivePanel=findViewById(R.id.client_archive_panel);archiveRow1=findViewById(R.id.client_archive_row1);archiveRow2=findViewById(R.id.client_archive_row2);archiveTitle=findViewById(R.id.client_archive_title);
        View bottomArea=findViewById(R.id.client_bottom_bar_area);
        bottomArea.setOutlineProvider(new android.view.ViewOutlineProvider(){@Override public void getOutline(View view,android.graphics.Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),dp(20));}});
        bottomArea.setClipToOutline(true);
        /* 底栏是悬浮叠层：按它的实际高度给列表留出底部空间，最后一行不会被压住
           （列表 clipToPadding=false，内容可以滚到悬浮栏下方）。 */
        bottomArea.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->reserveBottomSpace(bottomArea));
        FrameLayout holder=findViewById(R.id.client_context_actions);actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);holder.addView(actions,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        readTabs();renderTabs();
        tabBar.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener(){public void onTabSelected(TabLayout.Tab t){if(!rebuilding){
            /* 手势换页会在淡出结束后才 select；顶部直接点标签则已经选中，仍要走同一段过渡。 */
            if(tabFading){activateTab(t.getPosition());return;}
            if(t.getPosition()!=active)switchTabWithFade(t.getPosition());
        }}public void onTabUnselected(TabLayout.Tab t){}public void onTabReselected(TabLayout.Tab t){}});
        buildBottomBar();
        findViewById(R.id.client_up).setOnClickListener(v->up());
        findViewById(R.id.client_search).setOnClickListener(v->{if(search.getVisibility()==View.VISIBLE)hideSearch();else{search.setVisibility(View.VISIBLE);search.requestFocus();}});
        findViewById(R.id.client_breadcrumb_bar).addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            // 宽度跟随面包屑栏：左边让开上级按钮（52dp），右边让开搜索/排序/更多三个按钮（144dp）+ 8dp 间隙。
            // 不设固定上限，宽屏上搜索框自然变长。
            ViewGroup.LayoutParams params=search.getLayoutParams();int width=Math.max(dp(48),r-l-dp(204));
            if(params.width!=width){params.width=width;search.setLayoutParams(params);}});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int c){if(!tabs.isEmpty()){tab().query=s.toString();adapter.notifyDataSetChanged();updateActions();}}public void afterTextChanged(Editable e){}});
        findViewById(R.id.client_sort).setOnClickListener(v->sortMenu());
        findViewById(R.id.client_more).setOnClickListener(this::folderMenu);
        NavigationView nav=findViewById(R.id.client_sidebar);sidebarNav=nav;
        sidebarPage=findViewById(R.id.client_sidebar_page);sidebarPageBody=findViewById(R.id.client_page_body);sidebarPageTitle=findViewById(R.id.client_page_title);
        sidebarPageBody.setPadding(0,0,0,dp(8));
        drawer.setDrawerElevation(0);
        ViewCompat.addAccessibilityAction(findViewById(R.id.client_sidebar_host),"关闭侧边栏",(v,args)->{drawer.closeDrawers();return true;});
        /* 下载页专属：左下角「编辑」浮动按钮 → 打开下载设置子页（代理 / tracker / 目录 / ed2k 服务器）。
           显隐跟随 openPage/closePage 中的 body 类型控制（只有 DownloadSidebarPage 显示）。 */
        View pageEdit=findViewById(R.id.client_page_edit);
        pageEdit.setOnClickListener(v->openPage("下载设置",downloadSettingsPage()));
        pageEdit.bringToFront();pageEdit.invalidate();
        drawer.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)fitSidebarWidth();});
        drawer.post(this::fitSidebarWidth);
        /* 侧边栏功能全部在侧边栏内以二级页面展示，不再弹对话框。 */
        nav.setNavigationItemSelectedListener(item->{int id=item.getItemId();
            if(id==R.id.client_action_downloads)openPage("磁力／ed2k 下载",new DownloadSidebarPage(this));
            else if(id==R.id.client_action_sponsor)openPage("赞助开发者",sponsorPage());
            else if(id==R.id.client_action_factory){closePageAndDrawer();startActivity(new Intent(this,FormatFactoryActivity.class));}
            else if(id==R.id.client_action_appearance)openPage("个性化",appearancePage());
            else if(id==R.id.client_action_about)openPage("关于软件",aboutPage());
            else if(id==R.id.client_action_settings)openPage("设置",settingsPage());
            else if(id==R.id.client_action_transfers)openPage("传输任务",tasksPage());
            else if(id==R.id.client_action_network)openPage("网络连接",networkPage());
            else if(id==R.id.client_action_default_folder){closePage();drawer.closeDrawers();openDefault();}
            else openPage("个性化",appearancePage());return true;});
        /* 顶部「内部存储 / TF 卡」卡片本身就是入口：侧边栏菜单里不再重复这两项。
           卡片已从 NavigationView 里拿出来（否则会跟着菜单一起沉底），直接按 id 取。 */
        sidebarHeader=findViewById(R.id.client_storage_cards);
        dockSidebarMenuToBottom(nav);
        /* 赞过之后入口按需隐藏：prefs 里有记录就把「赞助开发者」这项永久收起。 */
        if(prefs.getBoolean("sponsor_hidden",false)){android.view.MenuItem sponsor=nav.getMenu().findItem(R.id.client_action_sponsor);if(sponsor!=null)sponsor.setVisible(false);}
        musicControls=new MusicControls(this);FrameLayout.LayoutParams mlp=new FrameLayout.LayoutParams(-1,-2);mlp.leftMargin=mlp.rightMargin=dp(6);mlp.topMargin=dp(6);((FrameLayout)findViewById(R.id.client_music_player)).addView(musicControls,mlp);
        sidebarHeader.findViewById(R.id.client_host_internal).setOnClickListener(v->{closePage();drawer.closeDrawers();openFullStorage("/storage/emulated/0");});
        sidebarHeader.findViewById(R.id.client_host_sd).setOnClickListener(v->{closePage();drawer.closeDrawers();chooseTfRoot();});
        /* 抽屉一打开就回到侧边栏主界面：底栏 ≡ 打开侧边栏，
           只有 openPage 主动弹开的那一次（pendingPageOpen）才保留二级页面。 */
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener(){@Override public void onDrawerOpened(View v){
            if(pendingPageOpen)pendingPageOpen=false;else closePage();
            refreshHeader();}});
        refreshHeader();
        ViewCompat.addAccessibilityAction(list,getString(R.string.client_drawer_accessibility),(v,args)->{openSidebarMain();return true;});
        installSwipe();installPullRefresh();cleanExpiredExports();loadBackground();search.setText(tab().query);load();
        /* 换肤/切深浅色会重建界面，重建前记下要回到的页面，这里恢复抽屉并重开该页。
           注意：只在真的存活的实例里清除标记——切换深浅色可能触发两轮重建，
           被销毁的那一轮不能把标记消费掉，否则新实例回来时页面就丢了。 */
        final String reopen=prefs.getString("reopen_page",null);
        if(reopen!=null){
            drawer.post(()->{if(isFinishing()||isDestroyed())return;
                /* 必须让 openPage 自己开抽屉：它会把 pendingPageOpen 置位，
                   否则 onDrawerOpened 会以为用户是要回主界面，刚恢复的页面会被立刻关掉。 */
                if("个性化".equals(reopen))openPage("个性化",appearancePage());
                else drawer.openDrawer(androidx.core.view.GravityCompat.START);
                prefs.edit().remove("reopen_page").apply();});
        }
        getWindow().getDecorView().post(()->SystemBrightnessPermission.requestOnceOnLaunch(this,prefs));
        if(getIntent().hasExtra("download_link"))handleDownloadIntent(getIntent());if(getIntent().hasExtra("open_downloads"))openDownloadsPage(getIntent());if(getIntent().hasExtra("open_path"))openPath(getIntent().getStringExtra("open_path"));
        if(getIntent().hasExtra("archive_dir")&&getIntent().hasExtra("archive_file"))handleArchiveIntent(getIntent());
        if(state==null&&Intent.ACTION_MAIN.equals(getIntent().getAction())&&getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)){/* 音乐模式已移除：启动不再跳转 */}
    }
    /** 外部应用「打开」压缩包后，定位到文件所在目录并选中该文件。 */
    private void handleArchiveIntent(Intent intent){
        final String dir=intent.getStringExtra("archive_dir");
        final String file=intent.getStringExtra("archive_file");
        intent.removeExtra("archive_dir");intent.removeExtra("archive_file");
        if(dir==null||file==null)return;
        findViewById(android.R.id.content).post(()->{if(isFinishing()||isDestroyed())return;openArchiveAt(dir,file);});
    }
    private void openArchiveAt(String dir,String fileName){
        File folder=new File(dir);
        if(!folder.isDirectory()){toast("无法定位压缩包目录");return;}
        pendingArchiveFile=new File(folder,fileName).getAbsolutePath();
        if(tab().remote()||!folder.getAbsolutePath().equals(tab().current())){openFullStorage(dir,true);return;}
        load();
    }
    private void applyPendingArchiveSelection(){
        if(pendingArchiveFile==null)return;
        String want=pendingArchiveFile;pendingArchiveFile=null;
        for(Entry e:entries){
            if(e.uri.equals(want)){
                selected.add(e.uri);adapter.notifyDataSetChanged();updateActions();
                int pos=entries.indexOf(e);
                if(pos>=0)((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(pos,dp(8));
                break;
            }
        }
    }
    private String storageSummary(){android.os.StatFs s=new android.os.StatFs(getFilesDir().getPath());return Formatter.formatFileSize(this,s.getTotalBytes()-s.getAvailableBytes())+" 已用 / "+Formatter.formatFileSize(this,s.getTotalBytes());}
    private void readTabs(){try{JSONArray a=new JSONArray(prefs.getString("tabs","[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);TabState t=new TabState();t.tree=o.optString("tree");t.profileId=o.optString("profileId");t.label=o.optString("label","新标签");t.sort=o.optInt("sort");t.desc=o.optBoolean("desc");t.folders=o.optBoolean("folders",true);JSONArray p=o.optJSONArray("stack");if(p!=null)for(int j=0;j<p.length();j++){JSONObject loc=p.getJSONObject(j);t.stack.add(new Place(loc.getString("uri"),loc.getString("name")));}tabs.add(t);}}catch(Exception e){tabs.clear();}if(tabs.isEmpty()){TabState t=new TabState();t.tree="FULL";t.label="内部存储";t.stack.add(new Place("/storage/emulated/0","内部存储"));tabs.add(t);}
for(TabState t:tabs){if(!t.remote()&&!"FULL".equals(t.tree)){t.tree="FULL";t.label="内部存储";t.stack.clear();t.stack.add(new Place("/storage/emulated/0","内部存储"));}if(!t.stack.isEmpty()&&"/storage/emulated/0".equals(t.stack.get(0).uri))t.stack.get(0).name="内部存储";}
active=Math.max(0,Math.min(prefs.getInt("active",0),tabs.size()-1));}
    private void persist(){try{JSONArray a=new JSONArray();for(TabState t:tabs){JSONObject o=new JSONObject();o.put("tree",t.tree);o.put("profileId",t.profileId);o.put("label",t.label);o.put("sort",t.sort);o.put("desc",t.desc);o.put("folders",t.folders);JSONArray p=new JSONArray();for(Place l:t.stack){JSONObject v=new JSONObject();v.put("uri",l.uri);v.put("name",l.name);p.put(v);}o.put("stack",p);a.put(o);}prefs.edit().putString("tabs",a.toString()).putInt("active",active).apply();}catch(JSONException ignored){}}
    private void saveScroll(){if(!tabs.isEmpty())tab().scroll=((LinearLayoutManager)list.getLayoutManager()).findFirstVisibleItemPosition();}
    private void renderTabs(){if(!tabs.isEmpty())remoteSessions.activate(tab().profileId);rebuilding=true;tabBar.removeAllTabs();
        ColorStateList iconTint=new ColorStateList(new int[][]{{android.R.attr.state_selected},{}},new int[]{attrColor(R.attr.client_accent),attrColor(R.attr.client_text_secondary)});
        for(int i=0;i<tabs.size();i++){final int index=i;View v=getLayoutInflater().inflate(R.layout.client_tab,tabBar,false);
            ((TextView)v.findViewById(R.id.client_tab_title)).setText(tabs.get(i).title());
            ImageView symbol=v.findViewById(R.id.client_tab_icon);symbol.setImageResource(tabIcon(tabs.get(i)));androidx.core.widget.ImageViewCompat.setImageTintList(symbol,iconTint);
            v.setSelected(i==active);
            float tabScale=i==active?1f:0.85f;
            // 缩放以左上为轴：pivotX=0 向左收起；pivotY=底边（缩小后底边对齐，避免上下都缩导致错位）。
            v.setPivotX(0f);v.setPivotY(dp(38));
            v.setScaleX(tabScale);v.setScaleY(tabScale);
            // 未选中标签整体下沉 2px，让底边与选中标签贴合（缩放后高度变小，需下移补位）。
            v.setTranslationY(i==active?0f:2f);
            v.findViewById(R.id.client_tab_close).setOnClickListener(x->{if(tabs.size()==1){warn("至少保留一个标签页");return;}saveScroll();tabs.remove(index);if(index<active)active--;else if(active>=tabs.size())active=tabs.size()-1;selected.clear();renderTabs();search.setText(tab().query);loadTab();});
            TabLayout.Tab t=tabBar.newTab().setCustomView(v);tabBar.addTab(t,i==active);spaceTab(v,i==active);}
        View add=getLayoutInflater().inflate(R.layout.client_tab_new,tabBar,false);
        add.setPivotX(0f);add.setPivotY(dp(38));add.setScaleX(0.85f);add.setScaleY(0.85f);add.setTranslationY(2f);
        newTabAnchor=add;
        add.setOnClickListener(x->newTabMenu());
        TabLayout.Tab addTab=tabBar.newTab().setCustomView(add);tabBar.addTab(addTab,false);spaceTab(add,false);
        rebuilding=false;persist();}
    /* 上限只统计真实标签，不含标签流末尾的“新建”按钮。
       手动新增（+ 菜单 / 打开本地目录）封顶 3 个；
       连接网络位置（含快速访问与远程收藏）放宽到 4 个，超限一律提示同一句话。 */
    /** 标签上限受「平板模式」控制：开启后屏更大，可显示更多标签。 */
    private int maxLocalTabs(){return prefs.getBoolean("tablet_mode",false)?5:3;}
    private int maxAllTabs(){return prefs.getBoolean("tablet_mode",false)?6:4;}
    private boolean canNewTab(){if(tabs.size()>=maxLocalTabs()){warn("标签已达上限，请先关闭一个标签页再新建");return false;}return true;}
    private boolean canNewRemoteTab(){if(tabs.size()>=maxAllTabs()){warn("标签已达上限，请先关闭一个标签页再连接");return false;}return true;}
    /** 相邻标签之间留出均匀缝隙，互不遮挡；未选中标签按 85% 缩放后，把缩放收缩的宽度从右 margin 完整扣回
        （负 margin 让下一个标签向左贴合），使标签向左靠拢紧凑排列，而不是留下收缩空隙显得分散。 */
    private void spaceTab(final View label,final boolean selected){
        final View holder=(View)label.getParent();
        if(!(holder instanceof ViewGroup))return;
        final ViewGroup group=(ViewGroup)holder;
        group.setClipChildren(false);
        // 等标签完成布局后再测量真实宽度（含 0.85 缩放），据此把右侧 margin 收紧（允许为负以贴紧）。
        label.addOnLayoutChangeListener(new android.view.View.OnLayoutChangeListener(){
            @Override public void onLayoutChange(android.view.View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){
                if(v.getWidth()<=0)return;   // 尚未测量完成，等下一次 layout
                v.removeOnLayoutChangeListener(this);
                if(!(group.getLayoutParams() instanceof ViewGroup.MarginLayoutParams))return;
                ViewGroup.MarginLayoutParams margin=(ViewGroup.MarginLayoutParams)group.getLayoutParams();
                margin.leftMargin=0;
                // 缩放围绕 pivotX=0（左边缘），右边向内收缩 shrink；从 2dp 基础缝隙里完整扣掉，负值即向左贴合。
                int shrink=selected?0:Math.round(v.getWidth()*0.15f);
                margin.rightMargin=dp(2)-shrink;
                group.setLayoutParams(margin);
            }
        });
    }
    /** 「＋」菜单：逐项校验上限；网络位置入口仍可使用。 */
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleDownloadIntent(intent);if(intent.hasExtra("open_downloads"))openDownloadsPage(intent);if(intent.hasExtra("open_path"))openPath(intent.getStringExtra("open_path"));if(intent.hasExtra("archive_dir")&&intent.hasExtra("archive_file"))handleArchiveIntent(intent);}
    /** 格式工厂「输出目录」按钮：把这个目录直接开成一个新标签页。
        延到布局就绪之后再做——openFullStorage 会改标签并触发加载，太早调用会和首帧抢。 */
    private void openPath(String path){
        if(path==null||path.isEmpty())return;
        final String target=path;
        findViewById(android.R.id.content).post(()->{if(isFinishing()||isDestroyed())return;openFullStorage(target,true);});
    }
    /** 下载通知点击：直接打开侧边栏的下载页（旧的全屏下载页已删除）。 */
    private void openDownloadsPage(Intent intent){intent.removeExtra("open_downloads");
        findViewById(android.R.id.content).post(()->{if(isFinishing()||isDestroyed())return;openPage("磁力／ed2k 下载",new DownloadSidebarPage(this));});}
    private void handleDownloadIntent(Intent intent){String raw=intent.getStringExtra("download_link");intent.removeExtra("download_link");if(raw==null)return;try{String link=DownloadLink.parse(raw).uri;findViewById(android.R.id.content).post(()->{if(isFinishing()||isDestroyed())return;DownloadSidebarPage download=new DownloadSidebarPage(this);openPage("磁力／ed2k 下载",download);download.post(()->download.addDownload(link));});}catch(Exception e){toast("下载链接无效");}}
    /** 弹窗菜单行的图标+文字数据源：新标签页 / 更多操作 / 目录属性三处共用同一套菜单渲染。
        toString 返回文案：SelectionToolbarUiTest 靠它断言选项文案。 */
    private static final class MenuItem{final String label;final int icon;
        MenuItem(String label,int icon){this.label=label;this.icon=icon;}
        @Override public String toString(){return label;}}
    /** 「＋」按钮（新标签页菜单的锚点）：renderTabs 时刷新，「打开的标签」弹窗的新增按钮也复用它当锚点。 */
    private View newTabAnchor;
    /** 三个图标菜单共用的弹出窗：与目录属性菜单同一外观——18dp 圆角卡片 + 小标题 + 48dp 图标行，行间 2dp。 */
    private PopupWindow menuPopup;
    /** 弹窗里的「图标+文字」行：24dp 图标（次级色 tint）+ 14sp 文字 + 10dp 圆角 ripple，行高 48dp。 */
    private LinearLayout iconMenuRow(String label,int iconRes){return iconMenuRow(label,androidx.appcompat.content.res.AppCompatResources.getDrawable(this,iconRes));}
    private LinearLayout iconMenuRow(String label,android.graphics.drawable.Drawable iconDrawable){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),0,dp(12),0);
        row.setBackgroundResource(R.drawable.client_folder_menu_row);row.setClickable(true);row.setFocusable(true);
        ImageView icon=new ImageView(this);icon.setImageDrawable(iconDrawable);icon.setImageTintList(ColorStateList.valueOf(attrColor(R.attr.client_text_secondary)));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));
        TextView text=new TextView(this);text.setText(label);text.setTextSize(14);text.setTextColor(attrColor(R.attr.client_text));text.setSingleLine(true);text.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);textParams.setMarginStart(dp(14));row.addView(text,textParams);
        return row;
    }
    /** 构建图标菜单弹窗（不含定位）：title 传 null 则不显示小标题；行间 2dp。定位由调用方决定。 */
    private PopupWindow buildIconMenu(String title,java.util.List<MenuItem> items,java.util.function.IntConsumer onClick){
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(8),title==null?dp(8):dp(4),dp(8),dp(8));
        if(title!=null){TextView head=new TextView(this);head.setText(title);head.setTextSize(12);head.setTextColor(attrColor(R.attr.client_text_secondary));head.setPadding(dp(12),dp(8),dp(12),dp(6));column.addView(head);}
        for(int n=0;n<items.size();n++){
            final int index=n;
            LinearLayout row=iconMenuRow(items.get(n).label,items.get(n).icon);
            row.setOnClickListener(v->{popupDismiss();onClick.accept(index);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48));p.bottomMargin=dp(2);
            column.addView(row,p);
        }
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.addView(column);
        int width=Math.min(dp(280),Math.max(dp(180),getWindow().getDecorView().getWidth()-dp(24)));
        int headPad=title==null?16:40;
        int height=Math.min(dp(headPad+50*items.size()),Math.max(dp(96),getWindow().getDecorView().getHeight()-dp(24)));
        PopupWindow popup=new PopupWindow(scroll,width,height,true);
        popup.setBackgroundDrawable(androidx.appcompat.content.res.AppCompatResources.getDrawable(this,R.drawable.client_folder_menu_background));
        popup.setElevation(dp(12));popup.setOutsideTouchable(true);popup.setClippingEnabled(true);
        popup.setOnDismissListener(()->{if(menuPopup==popup)menuPopup=null;});
        return popup;
    }
    /** 统一图标菜单：锚定在 anchor 下方（新标签页 / 更多操作 / 目录属性用）。 */
    private void showIconMenu(View anchor,String title,java.util.List<MenuItem> items,java.util.function.IntConsumer onClick){
        if(menuPopup!=null)menuPopup.dismiss();
        PopupWindow popup=buildIconMenu(title,items,onClick);
        menuPopup=popup;
        popup.showAsDropDown(anchor,0,dp(6),Gravity.END);
    }
    private void popupDismiss(){if(menuPopup!=null)menuPopup.dismiss();}
    /** 新标签页菜单：固定入口 + 已保存的网络位置（点击直接连接开标签，上限走 canNewRemoteTab）。 */
    private void newTabMenu(){
        View anchor=newTabAnchor!=null?newTabAnchor:findViewById(android.R.id.content);
        java.util.List<MenuItem> items=new ArrayList<>(java.util.Arrays.asList(
                new MenuItem("内部存储",R.drawable.client_ic_menu_storage),
                new MenuItem("TF 卡",R.drawable.client_ic_menu_sd),
                new MenuItem("打开默认目录",R.drawable.client_ic_menu_default),
                new MenuItem("添加网络位置",R.drawable.client_ic_menu_network),
                new MenuItem("收藏的图片",R.drawable.client_ic_menu_photo)));
        final java.util.List<String> savedIds=new ArrayList<>();
        for(java.util.Map.Entry<String,String> saved:connections.names().entrySet()){
            savedIds.add(saved.getKey());
            items.add(new MenuItem(saved.getValue()==null?saved.getKey():saved.getValue(),R.drawable.client_ic_menu_network));
        }
        showIconMenu(anchor,"新标签页",items,n->{
        if(n==3){openPage("网络位置",networkPage());return;}
        if(n>=5){final String id=savedIds.get(n-5);io.execute(()->{try{final ConnectionStore.Profile profile=connections.get(id);runOnUiThread(()->{if(!isDestroyed())connectProfile(profile);});}catch(Exception e){runOnUiThread(()->error(e));}});return;}
        if(!canNewTab())return;
        if(n==4){openPhotoFavorites();return;}if(n==0)openFullStorage("/storage/emulated/0",true);else if(n==1)chooseTfRoot(true);else openDefault();});}
    private boolean photoFavoritesTab(){return !tabs.isEmpty()&&"PHOTO_FAVORITES".equals(tab().tree);}
    private void openPhotoFavorites(){saveScroll();TabState t=new TabState();t.tree="PHOTO_FAVORITES";t.label="收藏的图片";t.stack.add(new Place("photos:favorites",t.label));tabs.add(t);active=tabs.size()-1;selected.clear();renderTabs();search.setText("");load();}
    private void loadPhotoFavorites(TabState target,int ticket,String anchor,int anchorOffset,int restoreIndex){path.setText("收藏的图片");final String key=dirKey(target);io.execute(()->{ArrayList<Entry> found=new ArrayList<>();for(java.util.Map.Entry<String,?> saved:getSharedPreferences("photo_favorites",MODE_PRIVATE).getAll().entrySet()){if(!Boolean.TRUE.equals(saved.getValue()))continue;int split=saved.getKey().indexOf('|');if(split<0)continue;Entry e=new Entry();e.profileId=saved.getKey().substring(0,split);e.uri=saved.getKey().substring(split+1);e.name=e.uri.substring(e.uri.lastIndexOf('/')+1);e.mime=mimeFor(e.name);if(e.profileId.isEmpty()&&!e.uri.startsWith("content:")){File f=new File(e.uri);if(!f.isFile())continue;e.name=f.getName();e.size=f.length();e.time=f.lastModified();}found.add(e);}runOnUiThread(()->applyLoad(ticket,key,()->{entries.clear();entries.addAll(found);path.setText(found.isEmpty()?"收藏的图片 · 暂无收藏":"收藏的图片");sortEntries();scrollAfterLoad(anchor,anchorOffset,restoreIndex);}));});}
    private void removePhotoFavorites(){android.content.SharedPreferences.Editor edit=getSharedPreferences("photo_favorites",MODE_PRIVATE).edit();for(Entry e:selectedEntries())edit.remove(e.profileId+"|"+e.uri);edit.apply();load();}
    private void allTabs(){String[] names=new String[tabs.size()];for(int i=0;i<names.length;i++)names[i]=tabs.get(i).title();new MaterialAlertDialogBuilder(this).setTitle("打开的标签").setSingleChoiceItems(names,active,(d,n)->{saveScroll();active=n;selected.clear();renderTabs();search.setText(tab().query);loadTab();d.dismiss();}).setNeutralButton("新增",(d,w)->newTabMenu()).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==ImagePreviewActivity.REQUEST_PREVIEW||request==EDIT_IMAGE){loadInternal(true,false);return;}
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();try{if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)throw new SecurityException("没有读取授权");
        getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);if(request==PICK_BACKGROUND)importBackground(uri);}catch(Exception e){error(e);}}
    private void openDefault(){String dest=prefs.getString("default_uri","");if(dest.isEmpty()){toast("先进入一个目录，然后在菜单中设为默认");return;}if(!new File(dest).isDirectory()){toast("默认目录不可用，请重新设置");return;}openFullStorage(dest,true);}
    private void up(){if(!selected.isEmpty()){selected.clear();adapter.notifyDataSetChanged();updateActions();return;}if(tab().stack.size()>1){tab().stack.remove(tab().stack.size()-1);tab().scroll=0;renderTabs();load();}else toast("已到根目录");}
    @Override public void onBackPressed(){
        if(sidebarPageOpen()){closePage();return;}
        if(drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)){drawer.closeDrawers();return;}
        if(!selected.isEmpty()){selected.clear();adapter.notifyDataSetChanged();updateActions();return;}
        if(search.getVisibility()==View.VISIBLE){hideSearch();return;}
        if(tab().stack.size()>1){up();return;}
        long now=System.currentTimeMillis();
        if(now-lastBackTime<2000){super.onBackPressed();return;}
        lastBackTime=now;toast(getString(R.string.client_exit_hint));}
    /** 常规重载：进入目录、文件操作完成、手动刷新都走这里。
        读完后按「原来第一行是谁」找回位置——**绝不能按旧下标跳转**：
        下标来自上一次切标签时存下的位置，删掉几个文件后它会指到列表中间，
        看起来就像最上面的文件夹凭空消失了。 */
    private void load(){loadInternal(false,false);}
    /** 切换标签页：沿用该标签自己上次的浏览位置（这是标签页的记忆，与内容变化无关）。 */
    private void loadTab(){loadInternal(false,true);}
    /** 下拉刷新：刷新期间保留当前列表，读完再整体替换（不闪空白）。 */
    private void loadRefresh(){loadInternal(true,false);}
    private String dirKey(TabState t){return t.remote()?("r:"+t.profileId+":"+t.current()):("l:"+t.current());}
    private TabState imageReturnTab;private String imageReturnKey,imageReturnAnchor;private int imageReturnOffset,imageReturnIndex;
    private void rememberImagePosition(){imageReturnTab=tab();imageReturnKey=dirKey(tab());imageReturnIndex=firstVisiblePosition();imageReturnOffset=firstVisibleOffset();ArrayList<Entry> visible=adapter.visible();imageReturnAnchor=imageReturnIndex>=0&&imageReturnIndex<visible.size()?visible.get(imageReturnIndex).name:null;saveScroll();}
    private void loadInternal(boolean keepList,boolean tabSwitch){
        remoteSessions.activate(tab().profileId);
        /* 上一次的刷新指示器还在转，这次读取不是刷新：先收掉，免得一直转。 */
        if(!keepList)cancelRefresh();
        final int ticket=++generation;final TabState target=tab();final String key=dirKey(target);
        /* 同一目录内的重载才有参照物：记住当前第一行是谁、它离顶边多少像素，读完按名字找回原位置；
           换了目录（或切换标签）就没有参照，回顶部；切标签按该标签自己存的下标恢复。 */
        final String anchorName;final int anchorOffset,anchorIndex;
        if(!tabSwitch&&target==imageReturnTab&&key.equals(imageReturnKey)){anchorName=imageReturnAnchor;anchorOffset=imageReturnOffset;anchorIndex=imageReturnIndex;}
        else if(tabSwitch){anchorName=null;anchorOffset=0;anchorIndex=target.scroll;}
        else if(key.equals(loadedKey)){
            ArrayList<Entry> visible=adapter.visible();int first=firstVisiblePosition();
            if(first>=0&&first<visible.size()){anchorName=visible.get(first).name;anchorOffset=firstVisibleOffset();anchorIndex=0;}
            else{anchorName=null;anchorOffset=0;anchorIndex=-1;}
        }else{anchorName=null;anchorOffset=0;anchorIndex=-1;}
        selected.clear();
        if(!keepList){entries.clear();adapter.notifyDataSetChanged();}
        path.setText(!target.remote()&&target.current().isEmpty()?"请点击 ＋ 打开目录":fullPath(target));updateActions();
        if("PHOTO_FAVORITES".equals(target.tree)){beginLoad(ticket,"正在读取图片收藏…");loadPhotoFavorites(target,ticket,anchorName,anchorOffset,anchorIndex);return;}
        if(!target.remote()&&target.current().isEmpty()){cancelLoadDialog();return;}
        if(target.remote()){path.setText(remoteSessions.connected(target.profileId)?"正在读取目录…":"正在连接…");beginLoad(ticket,"正在连接网络位置…");loadRemote(target,ticket,anchorName,anchorOffset,anchorIndex);return;}
        path.setText("正在读取目录…");if(!fullStorageGranted()){cancelLoadDialog();path.setText("未获得存储访问权限，请在侧边栏授权");requestFullAccess();return;}
        beginLoad(ticket,"正在读取目录…");loadFull(target,ticket,anchorName,anchorOffset,anchorIndex);}
    /** 读取完成后的唯一出口：等刷新动画满 1 秒再套用结果，并收敛指示器。
        generation 对不上说明这次读取已作废（用户切走了），直接丢弃。 */
    private void applyLoad(final int ticket,final String key,final Runnable update){
        Runnable apply=()->{if(isFinishing()||isDestroyed()||generation!=ticket)return;endLoad(ticket);loadedKey=key;update.run();if(tab()==imageReturnTab&&key.equals(imageReturnKey))imageReturnTab=null;};
        long wait=listGateUntil-SystemClock.uptimeMillis();
        if(wait>0)musicUi.postDelayed(apply,wait);else apply.run();
    }
    private int firstVisiblePosition(){RecyclerView.LayoutManager manager=list.getLayoutManager();return manager instanceof LinearLayoutManager?((LinearLayoutManager)manager).findFirstVisibleItemPosition():0;}
    /** 第一行离列表可视区顶边的像素偏移：恢复位置时一起用，刷新后画面不会跳。 */
    private int firstVisibleOffset(){View first=list.getChildAt(0);return first==null?0:first.getTop()-list.getPaddingTop();}
    /** 恢复列表位置：按名字找回原来那行（内容变了位置依然准）并还原像素偏移；找不到或没有参照就回顶部。 */
    private void scrollAfterLoad(String anchor,int anchorOffset,int restoreIndex){
        if(anchor!=null&&!anchor.isEmpty()){
            ArrayList<Entry> visible=adapter.visible();RecyclerView.LayoutManager manager=list.getLayoutManager();
            for(int i=0;i<visible.size();i++)if(anchor.equals(visible.get(i).name)){
                if(manager instanceof LinearLayoutManager)((LinearLayoutManager)manager).scrollToPositionWithOffset(i,anchorOffset);
                else list.scrollToPosition(i);
                return;}
            if(manager instanceof LinearLayoutManager)((LinearLayoutManager)manager).scrollToPositionWithOffset(Math.max(0,restoreIndex),anchorOffset);
            else list.scrollToPosition(Math.max(0,restoreIndex));return;
        }
        list.scrollToPosition(Math.max(0,restoreIndex));
    }
    /** 读取一律先把标签页切过去：只有真慢了（>500ms）才弹转圈，别让慢目录/慢网络把界面卡住。
        弹窗里的「取消」把这次读取作废（generation +1，回调全部失效），页面留在空白。 */
    private void beginLoad(final int ticket,final String text){
        cancelLoadDialog();
        loadDialogTicket=ticket;
        /* 下拉刷新已经有自己的指示器转着，不再叠一个转圈弹窗。 */
        if(refreshing)return;
        loadDialogTask=()->{if(isFinishing()||isDestroyed()||generation!=loadDialogTicket)return;
            LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(20),dp(24),0);
            ProgressBar spinner=new ProgressBar(this);spinner.setIndeterminate(true);
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(40),dp(40));sp.gravity=android.view.Gravity.CENTER_HORIZONTAL;box.addView(spinner,sp);
            TextView label=new TextView(this);label.setText(text);label.setTextSize(14);label.setGravity(android.view.Gravity.CENTER);label.setPadding(0,dp(12),0,0);label.setTextColor(attrColor(R.attr.client_text));box.addView(label);
            try{loadDialog=new MaterialAlertDialogBuilder(this).setView(box).setNegativeButton("取消",(d,w)->{generation++;path.setText("已取消 · 页面保持空白");}).create();loadDialog.show();}catch(Exception ignored){loadDialog=null;}};
        musicUi.postDelayed(loadDialogTask,500);
    }
    /** 读取结束（成功或失败）都要收掉转圈；ticket 对不上说明这次读取已作废，别去动别人的弹窗。 */
    private void endLoad(int ticket){if(ticket!=loadDialogTicket)return;cancelLoadDialog();endRefresh();}
    private void cancelLoadDialog(){
        if(loadDialogTask!=null){musicUi.removeCallbacks(loadDialogTask);loadDialogTask=null;}
        if(loadDialog!=null){try{if(loadDialog.isShowing())loadDialog.dismiss();}catch(Throwable ignored){}loadDialog=null;}
    }

    /* ===== 下拉/上拉刷新 =====
       列表已经到边界、用户还往那个方向继续拖时接管手势：顶部下拉刷新、列表底部上拉刷新。
       指示器做成从视口边缘「钻出来」的小圆片，拖动带阻尼、过半即触发；
       一旦触发，读取结果会被 listGateUntil 挡住——动画至少转满 1 秒，然后才换列表。 */
    private void installPullRefresh(){
        ViewGroup holder=(ViewGroup)list.getParent();
        if(holder==null||refreshPillTop!=null)return;
        refreshPillTop=refreshPill(holder,true);refreshPillBottom=refreshPill(holder,false);
    }
    private View refreshPill(ViewGroup holder,boolean top){
        FrameLayout pill=new FrameLayout(this);
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        background.setColor(attrColor(R.attr.client_surface_raised));background.setStroke(dp(1),attrColor(R.attr.client_outline));
        pill.setBackground(background);pill.setElevation(dp(6));
        ProgressBar spinner=new ProgressBar(this);spinner.setIndeterminate(true);
        spinner.setPadding(dp(7),dp(7),dp(7),dp(7));
        try{spinner.getIndeterminateDrawable().setTint(attrColor(R.attr.client_accent));}catch(Throwable ignored){}
        pill.addView(spinner,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(44),dp(44),top?(Gravity.TOP|Gravity.CENTER_HORIZONTAL):(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        if(top)params.topMargin=dp(8);else params.bottomMargin=dp(8);
        holder.addView(pill,params);
        /* 先藏在视口外面：拖动时按比例滑进来，像从边缘钻出来。 */
        pill.setTranslationY(top?-dp(56):dp(56));pill.setAlpha(0f);pill.setVisibility(View.INVISIBLE);
        return pill;
    }
    private View activePill(int direction){return direction<0?refreshPillBottom:refreshPillTop;}
    /** 拖动中的指示器：位移带阻尼（手感的来源），过阈值就显示完整一档。
        用 pullBase 记下「贴到边界那一刻」的位置——先滚列表再拉时，手指的净位移不能当拖动量。 */
    private void updatePull(float dy){
        float raw=Math.max(0f,Math.abs(dy-pullBase)-dp(18));
        pullDistance=Math.min(raw*0.55f,dp(PULL_TRIGGER_DP)*1.6f);
        float progress=Math.min(1f,pullDistance/dp(PULL_TRIGGER_DP));
        View pill=activePill(pullDirection);if(pill==null)return;
        pill.animate().cancel();pill.setVisibility(View.VISIBLE);
        pill.setAlpha(Math.min(1f,progress*1.5f));
        pill.setTranslationY(pullDirection<0?(dp(56)-progress*dp(70)):(-dp(56)+progress*dp(70)));
    }
    /** 松手：过阈值就刷新，没过就把指示器收回去。 */
    private void finishPull(){
        if(!pulling)return;
        boolean trigger=pullDistance>=dp(PULL_TRIGGER_DP);int direction=pullDirection;
        pulling=false;pullDirection=0;pullDistance=0;
        if(trigger)startRefresh(direction);else hideRefreshPills();
    }
    /** 触发刷新：指示器就位 → 记下「结果最早可套用时刻」→ 后台重读当前目录。 */
    private void startRefresh(int direction){
        if(refreshing)return;
        refreshing=true;
        View pill=activePill(direction);
        if(pill!=null){pill.animate().cancel();pill.setVisibility(View.VISIBLE);pill.animate().alpha(1f).translationY(direction<0?-dp(14):dp(14)).setDuration(120).start();}
        /* 无论重读多快，动画都至少停 1 秒再更新列表。 */
        listGateUntil=SystemClock.uptimeMillis()+1000;
        loadRefresh();
    }
    /** 收掉刷新态（读完了，或者这次刷新被别的读取顶掉）。 */
    private void endRefresh(){
        listGateUntil=0;
        if(!refreshing)return;
        refreshing=false;hideRefreshPills();
    }
    /** 这次读取不是刷新：把刷新态清掉（不带动画收尾，避免和列表更新打架）。 */
    private void cancelRefresh(){if(refreshing){refreshing=false;listGateUntil=0;hideRefreshPills();}}
    private void hideRefreshPills(){
        View[] pills={refreshPillTop,refreshPillBottom};float[] hidden={-dp(56),dp(56)};
        for(int i=0;i<pills.length;i++){
            final View pill=pills[i];if(pill==null)continue;
            final float target=hidden[i];
            pill.animate().cancel();
            /* 收尾时再确认一次 alpha：万一收的过程中用户又拉了一下，别把它藏掉。 */
            pill.animate().alpha(0f).translationY(target).setDuration(180).withEndAction(()->{if(pill.getAlpha()==0f)pill.setVisibility(View.INVISIBLE);}).start();
        }
    }
    private void loadFull(TabState target,int ticket,String anchor,int anchorOffset,int restoreIndex){final String dir=target.current(),key=dirKey(target);io.execute(()->{try{
        File folder=new File(dir);
        if(!folder.exists())throw new IOException("目录不存在："+dir);
        File[] files=folder.listFiles();
        if(files==null)throw new IOException("无法读取 "+dir+"\n请确认已授予完整存储访问权限，或该存储已挂载");
        ArrayList<Entry> found=new ArrayList<>();for(File f:files){Entry x=new Entry();x.uri=f.getAbsolutePath();x.name=f.getName();x.dir=f.isDirectory();x.mime=x.dir?DocumentsContract.Document.MIME_TYPE_DIR:mimeFor(x.name);x.size=x.dir?0:f.length();x.time=f.lastModified();found.add(x);}
        String name=folder.getName();if(name.isEmpty())name=folder.getAbsolutePath();final String shown=name;
        runOnUiThread(()->applyLoad(ticket,key,()->{entries.clear();entries.addAll(found);if(target.stack.size()>1)target.stack.get(target.stack.size()-1).name=shown;renderTabs();path.setText(fullPath(target));sortEntries();applyPendingArchiveSelection();scrollAfterLoad(anchor,anchorOffset,restoreIndex);}));
    }catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed()||generation!=ticket)return;endLoad(ticket);entries.clear();adapter.notifyDataSetChanged();path.setText("读取失败 · 页面保持空白");message("无法打开目录",reason(e));});}});}
    private void openFullStorage(String path){openFullStorage(path,false);}
    /** 打开本地目录：不再先做 isDirectory() 之类的探测——外置卡拔了或响应慢时那次 stat 能把主线程卡住。
        一律先把标签页切过去，读不到就由 loadFull 报错并让页面留在空白。 */
    private void openFullStorage(String path,boolean newTab){openLocalStorage(path,newTab,true);}
    /** 同上，但可跳过「完整存储访问」校验：应用私有缓存目录本来就能读，不该逼用户去开全盘权限。 */
    private void openLocalStorage(String path,boolean newTab,boolean requireGrant){if(requireGrant&&!fullStorageGranted()){requestFullAccess();return;}File root=new File(path);String rootName=root.getName();if(rootName.isEmpty()||rootName.equals("0"))rootName="内部存储";TabState t=new TabState();t.tree="FULL";t.label=rootName;t.stack.add(new Place(root.getAbsolutePath(),rootName));
        /* 当前标签是网络位置时不能就地替换（会丢掉 FTP 会话），必须另开一个：这属于“换个地方看”，
           走 4 个的宽松上限，否则 3 个标签里只要有一个是网络位置，内部存储就再也打不开了。 */
        if(newTab||tab().remote()){if(!(newTab?canNewTab():canNewRemoteTab()))return;tabs.add(t);active=tabs.size()-1;}else{tabs.set(active,t);}selected.clear();search.setText("");renderTabs();load();}
    private void chooseTfRoot(){chooseTfRoot(false);}
    private boolean fullStorageGranted(){if(Build.VERSION.SDK_INT>=30)return Environment.isExternalStorageManager();return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    private void sortEntries(){TabState t=activeTab();if(t==null)return;Collator cmp=Collator.getInstance();entries.sort((a,b)->{if(t.folders&&a.dir!=b.dir)return a.dir?-1:1;int c=t.sort==1?Long.compare(a.time,b.time):t.sort==2?Long.compare(a.size,b.size):t.sort==3?cmp.compare(a.mime==null?"":a.mime,b.mime==null?"":b.mime):cmp.compare(a.name==null?"":a.name,b.name==null?"":b.name);return(t.desc?-1:1)*c;});adapter.notifyDataSetChanged();updateActions();persist();}
    private void sortMenu(){View v=getLayoutInflater().inflate(R.layout.client_sort_sheet,null);int[] ids={R.id.client_sort_name,R.id.client_sort_time,R.id.client_sort_size,R.id.client_sort_type};RadioGroup field=v.findViewById(R.id.client_sort_field),direction=v.findViewById(R.id.client_sort_direction);CheckBox folders=v.findViewById(R.id.client_folders_first);field.check(ids[tab().sort]);direction.check(tab().desc?R.id.client_descending:R.id.client_ascending);folders.setChecked(tab().folders);new MaterialAlertDialogBuilder(this).setTitle("排序方式").setView(v).setPositiveButton("完成",(d,w)->{for(int n=0;n<ids.length;n++)if(field.getCheckedRadioButtonId()==ids[n])tab().sort=n;tab().desc=direction.getCheckedRadioButtonId()==R.id.client_descending;tab().folders=folders.isChecked();sortEntries();}).setNegativeButton("取消",null).show();}
    private ArrayList<Entry> selectedEntries(){ArrayList<Entry> a=new ArrayList<>();for(Entry x:entries)if(selected.contains(x.uri))a.add(x);return a;}
    /** 底栏双形态：常态显示 5 个功能图标，选中/剪贴板时原位切换为操作按钮（替换原 5 个按钮）。
        选中单个本地压缩包时**不替换**：像音乐播放那样在卡片顶部增高一块压缩包面板，
        下面那一行常态功能按钮原样留着 —— 所以 archive 模式按「无选中」处理底栏形态。 */
    private void updateActions(){
        boolean archiveMode=!selected.isEmpty()&&isArchiveSelection();
        /* 选中压缩包时**不再改变底栏形态**：长按出来的那一排操作按钮（分享/打开/重命名/删除/压缩/更多/取消）
           照原样留在下面，压缩包面板是额外增高的那一块，两者互不干扰。 */
        boolean barMode=selected.isEmpty()&&clipboard.isEmpty();
        actions.removeAllViews();
        if(bottomBar!=null)bottomBar.setVisibility(barMode?View.VISIBLE:View.GONE);
        if(contextHolder!=null)contextHolder.setVisibility(barMode?View.GONE:View.VISIBLE);
        setArchivePanel(archiveMode?selectedEntries().get(0):null);
        if(barMode)updateFavoriteIcon();
        else if(photoFavoritesTab()&&!selected.isEmpty()){
            View remove=action(R.drawable.client_bottom_icon_favorite,R.string.client_bottom_favorite,this::removePhotoFavorites);((TextView)((ViewGroup)remove).getChildAt(1)).setText("取消收藏");remove.setContentDescription("取消图片收藏");
            action(R.drawable.client_bottom_icon_cancel,R.string.client_cancel,()->{selected.clear();adapter.notifyDataSetChanged();updateActions();});
        }
        else if(!selected.isEmpty()){
            boolean local=selectedEntries().get(0).profileId.isEmpty();
            action(R.drawable.client_bottom_icon_share,R.string.client_share,()->external(selectedEntries(),false));
            action(R.drawable.client_bottom_icon_open_with,R.string.client_action_open,()->external(selectedEntries(),true));
            View renameAction=action(R.drawable.client_bottom_icon_rename,R.string.client_rename,this::rename);
            renameAction.setEnabled(selected.size()==1);renameAction.setAlpha(selected.size()==1?1f:0.4f);
            action(R.drawable.client_bottom_icon_delete,R.string.client_delete,this::delete);
            if(local)action(R.drawable.client_bottom_icon_add,R.string.client_compress,()->compress(selectedEntries()));
            View moreAction=action(R.drawable.client_bottom_icon_more,R.string.client_action_more,()->{});
            moreAction.setOnClickListener(v->selectionMenu(moreAction));
            /* 这里原本还有一颗「取消」，占了 1/7 的宽度。取消选中现在靠返回键／上级按钮／
               执行完操作自动清空，正好把让出来的宽度分给剩下 6 颗。 */
        }
        else{action(R.drawable.client_bottom_icon_paste,R.string.client_action_paste,this::paste);action(R.drawable.client_bottom_icon_cancel,R.string.client_cancel,()->{clipboard.clear();updateActions();});}
        evenChips(actions);
        if(lastBarMode!=-1&&lastBarMode!=(barMode?1:0))animateBarSwitch(barMode);
        lastBarMode=barMode?1:0;}
    private boolean isArchiveSelection(){
        if(selected.size()!=1)return false;
        Entry e=selectedEntries().get(0);
        return e.profileId.isEmpty()&&!e.dir&&ArchiveUtil.isArchive(e.name);
    }
    private void buildArchivePanel(Entry e){
        archiveRow1.removeAllViews();archiveRow2.removeAllViews();
        archiveTitle.setText(e.name);
        archiveUri=e.uri;
        /* 面板只放压缩包专属操作，且排在两行里让每颗按钮都有足够宽度（文字不再被截成「解压到…」）。
           密码不在这里：归档真的加密时才弹窗要，见 extract()。 */
        action(R.drawable.client_bottom_icon_download,R.string.client_extract_here,()->extract(e,false),archiveRow1);
        action(R.drawable.client_bottom_icon_add,R.string.client_extract_new_folder,()->extract(e,true),archiveRow1);
        action(R.drawable.client_bottom_icon_open_with,R.string.client_archive_view,()->viewContents(e),archiveRow2);
        action(R.drawable.client_bottom_icon_more,R.string.client_archive_verify,()->verifyArchive(e),archiveRow2);
        evenChips(archiveRow1);evenChips(archiveRow2);
    }
    private void exitArchiveMode(){selected.clear();adapter.notifyDataSetChanged();updateActions();}
    /** 压缩包面板的显隐：只在「有／无」翻转时才重建并播放高度动画；
        同一个文件上反复 updateActions（搜索输入、排序）只复用，不闪一下。 */
    private void setArchivePanel(Entry e){
        if(archivePanel==null)return;
        boolean want=e!=null;
        if(want&&(e.uri==null?!archiveUri.equals(""):!e.uri.equals(archiveUri)))buildArchivePanel(e);
        if(want==archiveShown)return;
        archiveShown=want;
        animateArchivePanel(want);
    }
    /** 面板展开／收起：高度在 0 与实测值之间补间，外层悬浮卡片随之平滑增高／变矮
        —— 与音乐播放控件那一块完全同一套做法。 */
    private void animateArchivePanel(boolean show){
        if(archiveAnim!=null)archiveAnim.cancel();
        archivePanel.animate().cancel();
        ViewGroup.LayoutParams lp=archivePanel.getLayoutParams();
        if(lp==null){archivePanel.setVisibility(show?View.VISIBLE:View.GONE);archivePanel.setAlpha(1f);return;}
        if(show){archivePanel.setVisibility(View.VISIBLE);archivePanel.setAlpha(0f);}
        int width=archivePanel.getWidth();
        int wSpec=width>0?View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY):View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED);
        archivePanel.measure(wSpec,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int target=Math.max(archivePanel.getMeasuredHeight(),dp(48));
        final int from=show?0:(lp.height>0?lp.height:Math.max(archivePanel.getHeight(),0));
        final int to=show?target:0;
        if(show)archivePanel.animate().alpha(1f).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator());
        else archivePanel.animate().alpha(0f).setDuration(180).setInterpolator(new android.view.animation.AccelerateInterpolator());
        lp.height=from;archivePanel.setLayoutParams(lp);
        archiveAnim=android.animation.ValueAnimator.ofInt(from,to);
        archiveAnim.setDuration(show?220:200);
        archiveAnim.setInterpolator(show?new android.view.animation.DecelerateInterpolator():new android.view.animation.AccelerateInterpolator());
        archiveAnim.addUpdateListener(a->{ViewGroup.LayoutParams p=archivePanel.getLayoutParams();if(p==null)return;p.height=(int)a.getAnimatedValue();archivePanel.setLayoutParams(p);});
        final boolean keep=show;
        archiveAnim.addListener(new android.animation.AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(android.animation.Animator a){
                ViewGroup.LayoutParams p=archivePanel.getLayoutParams();
                if(p!=null){p.height=ViewGroup.LayoutParams.WRAP_CONTENT;archivePanel.setLayoutParams(p);}
                archivePanel.setAlpha(1f);
                if(!keep&&!archiveShown)archivePanel.setVisibility(View.GONE);
            }});
        archiveAnim.start();
    }
    /** 长按选中/取消时底栏在「功能按钮」和「操作按钮」之间切换，做一次淡入上滑过渡。 */
    private void animateBarSwitch(boolean barMode){
        View incoming=barMode?bottomBar:contextHolder;
        if(incoming==null)return;
        incoming.setAlpha(0f);incoming.setTranslationY(dp(8));
        incoming.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    /** 选中/剪贴板态的底栏按钮：与常态功能按钮一致的“图标+文字”chip。 */
    private View action(int icon,int desc,Runnable click){
        return action(icon,desc,click,actions,1);
    }
    private View action(int icon,int desc,Runnable click,LinearLayout container){
        return action(icon,desc,click,container,1);
    }
    /** maxLines>1：按钮少、文字长时（压缩包面板）让文字折行显示，不再被截成「解压到…」。
        选中态那一排（actions 容器）少了「取消」，每颗能分到更多宽度，图标与文字一起放大一档。 */
    private View action(int icon,int desc,Runnable click,LinearLayout container,int maxLines){
        boolean large=container==actions;
        LinearLayout b=labeledChip(icon,desc,maxLines,large?22:20,large?11:10,large?6:8);
        b.setContentDescription(getString(desc));b.setTooltipText(getString(desc));
        b.setOnClickListener(v->click.run());
        container.addView(b,chipLayoutParams());
        return b;}
    /** 底栏 5 个功能按钮：图标在上、小字在下，文字在按钮内不增高按钮，靠压紧边距容纳。 */
    private void buildBottomBar(){
        if(bottomBar==null)return;
        bottomBar.removeAllViews();
        bottomLabelButton(R.drawable.client_bottom_icon_download,R.string.client_bottom_transfers,R.string.client_transfers,()->openPage("传输任务",tasksPage()));
        bottomLabelButton(R.drawable.client_bottom_icon_add,R.string.client_bottom_new,R.string.client_new_item,this::newItemMenu);
        bottomLabelButton(R.drawable.client_bottom_icon_menu,R.string.client_bottom_menu,R.string.client_drawer_open,this::openSidebarMain);
        View fav=bottomLabelButton(R.drawable.client_bottom_icon_favorite,R.string.client_bottom_favorite,R.string.client_favorite_dir,this::toggleFavoriteDir);
        favoriteButton=fav;favoriteIcon=(ImageView)((ViewGroup)fav).getChildAt(0);
        bottomLabelButton(R.drawable.client_bottom_icon_favorites,R.string.client_bottom_favorites,R.string.client_favorites,()->openPage("已收藏的目录",favoritesPage()));
        evenChips(bottomBar);
        updateFavoriteIcon();
    }
    private View bottomLabelButton(int icon,int label,int desc,Runnable onClick){
        LinearLayout b=labeledChip(icon,label);b.setContentDescription(getString(desc));b.setTooltipText(getString(desc));
        b.setOnClickListener(v->onClick.run());
        bottomBar.addView(b,chipLayoutParams());
        return b;
    }
    /** “图标在上、小字在下”的窄条 chip：weight 等宽铺满底栏，彼此留 4dp 间距。 */
    private LinearLayout labeledChip(int icon,int label){return labeledChip(icon,label,1,20,10,8);}
    /** iconDp / textSp / sidePad 让调用方按需放大：选中态那一排按钮少，图标文字都能大一号。 */
    private LinearLayout labeledChip(int icon,int label,int maxLines,int iconDp,int textSp,int sidePad){
        LinearLayout b=new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.client_bottom_chip);
        b.setClickable(true);b.setFocusable(true);
        ImageView img=new ImageView(this);img.setImageResource(icon);
        img.setImageTintList(ColorStateList.valueOf(attrColor(R.attr.client_text)));
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        /* 图标与文字之间留 3dp：放大后不至于贴在一起。 */
        b.addView(img,new LinearLayout.LayoutParams(dp(iconDp),dp(iconDp)));
        TextView t=new TextView(this);t.setText(getString(label));t.setTextSize(textSp);
        t.setGravity(Gravity.CENTER);t.setMaxLines(maxLines);t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextColor(attrColor(R.attr.client_text));t.setPadding(0,dp(3),0,0);
        /* 文字宽度自适应：一行放不下时先把字横向压扁（最窄到 72%），而不是折行或加省略号。
           用户明确不接受「解压到新文件夹」被截成两行或者「解压到…」。 */
        t.addOnLayoutChangeListener((v,l,tt,r,bb,ol,ot,or,ob)->squeeze(t));
        b.addView(t,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        /* 上下不留 padding、内容垂直居中（高度跟容器走），左右留 sidePad 给文字让位。 */
        b.setPadding(dp(sidePad),0,dp(sidePad),0);
        return b;
    }
    /** 把一行文字横向压缩到刚好放得下：textScaleX 只缩放字形本身，不影响其它元素的布局。 */
    private static void squeeze(TextView t){
        int available=t.getWidth()-t.getPaddingLeft()-t.getPaddingRight();
        float needed=t.getPaint().measureText(t.getText().toString());
        if(available<=0||needed<=0)return;
        float scale=Math.max(0.72f,Math.min(1f,available/needed));
        if(Math.abs(t.getTextScaleX()-scale)>0.01f)t.setTextScaleX(scale);
    }
    private LinearLayout.LayoutParams chipLayoutParams(){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1);
        lp.leftMargin=lp.rightMargin=dp(4);return lp;
    }
    /** 横向间距随按钮个数自适应：颗数越多间距越紧（≤4 颗 6dp、5 颗 4dp、6 颗 3dp、≥7 颗 2dp），给每颗按钮多省一点宽度。 */
    private void evenChips(LinearLayout bar){
        /* 选中态现在是 6 颗（去掉了「取消」），间距回到 4dp：挤到 3dp 反而不如让出那一格宽度给按钮本身。 */
        int n=bar.getChildCount();int gap=n>=7?dp(2):n>=5?dp(4):dp(6);
        for(int i=0;i<n;i++){LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)bar.getChildAt(i).getLayoutParams();
            // Match the player's 6dp outer inset, including pressed/held bounds.
            int left=i==0?0:gap,right=i==n-1?0:gap;
            if(lp.leftMargin!=left||lp.rightMargin!=right){lp.leftMargin=left;lp.rightMargin=right;bar.getChildAt(i).setLayoutParams(lp);}}
    }
    /** 悬浮底栏盖在列表之上，按它的实际高度给列表补底部留白（播放控件展开时会自动跟着变高）。 */
    private void reserveBottomSpace(View bottomArea){
        if(list==null||bottomArea==null)return;
        int reserve=bottomArea.getHeight()+dp(28);
        /* 播放控件展开/收起时高度逐帧变化，差几像素就不必重排列表，避免动画掉帧 */
        if(Math.abs(list.getPaddingBottom()-reserve)>dp(4))list.setPadding(list.getPaddingLeft(),list.getPaddingTop(),list.getPaddingRight(),reserve);
    }
    /* 播放器能直接吃的后缀（ExoPlayer 内置 mp4/mkv/webm/ts/flv/avi 等提取器）；
       rmvb/wmv/asf/ape 这类它解不了的刻意不放进来，免得给个点了必然失败的入口。 */
    private static final Set<String> VIDEO_EXT=new HashSet<>(Arrays.asList("mp4","m4v","mkv","webm","mov","3gp","3gpp","ts","m2ts","flv","avi","mpg","mpeg","ogv"));
    private static final Set<String> AUDIO_EXT=new HashSet<>(Arrays.asList("mp3","m4a","aac","flac","wav","ogg","oga","opus","amr","mka"));
    private static String extensionOf(String name){int dot=name==null?-1:name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);}
    private static String mimeOf(Entry e){return e.mime==null?"":e.mime.toLowerCase(Locale.ROOT);}
    private boolean isAudio(Entry e){String mime=mimeOf(e);if(mime.startsWith("audio/"))return true;if(mime.startsWith("video/"))return false;return AUDIO_EXT.contains(extensionOf(e.name));}
    /** 视频 / 音频：本地与远程都可播（远程走在线流式，不下载）。 */
    private boolean isPlayable(Entry e){if(e.dir)return false;String mime=mimeOf(e);return mime.startsWith("video/")||mime.startsWith("audio/")||VIDEO_EXT.contains(extensionOf(e.name))||AUDIO_EXT.contains(extensionOf(e.name));}
    /** 播放：本地文件直接读；远程媒体交给 FtpDataSource 按需流式读取，全程不落盘。 */
    private void playMedia(Entry e){
        if(isAudio(e)){if(connections.musicBlocked(e.profileId)){toast("音乐播放暂不支持该协议");return;}/* 队列 = 当前文件夹里所有能播的音频，顺序与列表一致：通知栏／耳机的上一首下一首才有东西可切。 */
            ArrayList<MusicTrack> queue=new ArrayList<>();for(Entry item:entries){if(item.dir||!isAudio(item)||connections.musicBlocked(item.profileId))continue;queue.add(new MusicTrack(item.name,item.uri,item.profileId,item.size,item.time));}
            try{MusicPlaybackService.playInFolder(this,new MusicTrack(e.name,e.uri,e.profileId,e.size,e.time),queue);}catch(Exception x){error(x);}return;}
        Intent intent=new Intent(this,VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_PATH,e.uri);
        intent.putExtra(VideoPlayerActivity.EXTRA_PROFILE,e.profileId==null?"":e.profileId);
        intent.putExtra(VideoPlayerActivity.EXTRA_TITLE,e.name);
        intent.putExtra(VideoPlayerActivity.EXTRA_SIZE,e.size);
        try{startActivity(intent);}catch(Exception x){error(x);}
    }
    /**
     * 点击文件：图片直接进预览，音视频直接用内置播放器播（远程走在线流式，不下载），
     * 不再弹「播放 / 用其他应用打开 / 分享」的选择菜单；这两项改由长按选中后底栏按钮触发。
     * 压缩包仍给解压菜单；其余文件走系统打开方式。
     */
    private void openFile(Entry e){if(!e.dir&&java.util.Arrays.asList("torrent","magnet","ed2k").contains(extensionOf(e.name))){if(!e.profileId.isEmpty()){toast("请先将链接文件下载到本地");return;}io.execute(()->{try{String link=DownloadInput.read(this,e.uri.startsWith("content:")?Uri.parse(e.uri):Uri.fromFile(new File(e.uri)));runOnUiThread(()->handleDownloadIntent(new Intent().putExtra("download_link",link)));}catch(Exception|LinkageError error){runOnUiThread(()->toast("无法读取下载链接"));}});return;}
        final ArrayList<Entry> one=new ArrayList<>();one.add(e);
        boolean local=e.profileId.isEmpty();
        if(!e.dir&&ImagePreviewActivity.isImage(e.name,mimeOf(e))){previewImage(e);return;}
        if(!e.dir&&(isPlayable(e))){playMedia(e);return;}
        boolean archive=local&&!e.dir&&ArchiveUtil.isArchive(e.name);
        if(!archive){external(one,true);return;}
        selected.add(e.uri);adapter.notifyDataSetChanged();updateActions();
    }
    private void previewImage(Entry selectedImage){
        ArrayList<ImagePreviewActivity.Item> pictures=new ArrayList<>();int start=0;
        for(Entry item:adapter.visible()){
            if(item.dir||!ImagePreviewActivity.isImage(item.name,mimeOf(item)))continue;
            if(item.uri.equals(selectedImage.uri)&&item.profileId.equals(selectedImage.profileId))start=pictures.size();
            pictures.add(new ImagePreviewActivity.Item(item.uri,item.name,item.profileId,mimeOf(item)));
        }
        if(pictures.isEmpty())return;
        rememberImagePosition();
        ImagePreviewActivity.launch(this,pictures,start);
    }
    private void editImage(Entry e){
        if(e.profileId==null||!e.profileId.isEmpty()){toast("远程图片请先下载到本地再编辑");return;}
        if(e.uri.startsWith("content:")){toast("该图片无法直接编辑，请先复制到本地目录");return;}
        Intent intent=new Intent(this,ImageEditorActivity.class);intent.putExtra(ImageEditorActivity.EXTRA_PATH,e.uri);
        try{rememberImagePosition();startActivityForResult(intent,EDIT_IMAGE);}catch(Exception x){imageReturnTab=null;error(x);}
    }
    /** 解压：toNewFolder=true 时解压到以压缩包命名的新文件夹。 */
    private void extract(Entry e,boolean toNewFolder){
        final File archive=new File(e.uri);
        File parent=archive.getParentFile();if(parent==null||!archive.exists()){toast("无法读取该压缩包");return;}
        File target;
        if(toNewFolder){
            String base=ArchiveUtil.stripExtension(archive.getName());target=new File(parent,base);
            int i=2;while(target.exists())target=new File(parent,base+" ("+(i++)+")");
        }else target=parent;
        final File destination=target;
        /* 「解压到新文件夹」用的是全新目录、不会撞名；「解压到当前目录」可能撞上已有文件，
           交给策略在第一个冲突时问一次。 */
        final java.util.concurrent.atomic.AtomicInteger skipped=new java.util.concurrent.atomic.AtomicInteger();
        final ArchiveUtil.Conflict conflict=toNewFolder?ArchiveUtil.Conflict.REPLACE:overwritePolicy(skipped);
        final java.nio.charset.Charset charset=archiveCharset;
        runArchiveJob("正在解压 "+archive.getName(),archive.getName(),progress->{
                /* 密码按需索要：归档真的加密才问一次；解开失败（多半是密码不对）再问，
                   最多重试 3 次，之后把原始错误抛出去照常提示。
                   needsPassword 对 7z 恒为 false（加密信息藏在编码链里），所以 7z 的
                   「先试后问」也要走：第一次失败若像密码问题就补弹输入框。 */
                String pwd=archivePassword==null||archivePassword.isEmpty()?null:archivePassword;
                if(pwd==null&&ArchiveUtil.needsPassword(archive))pwd=remember(askPassword(false,e));
                for(int attempt=0;;attempt++){
                    try{ArchiveUtil.extract(archive,destination,progress,conflict,pwd,charset);return;}
                    catch(Exception failure){
                        if(Thread.currentThread().isInterrupted())throw failure;
                        if(!looksLikePasswordTrouble(failure))throw failure;
                        if(attempt>=2)throw failure;
                        if(pwd==null&&attempt==0){pwd=remember(askPassword(false,e));continue;}
                        pwd=remember(askPassword(true,e));
                    }
                }
            },
            ()->toast(skipped.get()==0?"已解压到 "+destination.getName():"已解压到 "+destination.getName()+"（跳过 "+skipped.get()+" 个同名文件）"),
            ()->toast("解压已取消"));
    }
    /** 记住用户填过的密码（同一轮解压里接着用），用户放弃时抛「已取消」，
        注意它一定在后台线程调用：这里要同步等对话框，主线程调会死锁。 */
    private String remember(String password)throws IOException{
        if(password==null||password.isEmpty())throw new IOException("已取消");
        archivePassword=password;return password;
    }
    /** 背景线程里向用户要密码：wrong=true 表示上一次给出的密码不对，弹窗要换文案。
        返回 null = 用户放弃。runArchiveJob 的进度对话框是不可取消的模态框，这个对话框叠在它上面。 */
    private String askPassword(boolean wrong,Entry e){
        final java.util.concurrent.CountDownLatch answered=new java.util.concurrent.CountDownLatch(1);
        final String[] result={null};
        runOnUiThread(()->{
            if(isDestroyed()||isFinishing()){answered.countDown();return;}
            View view=getLayoutInflater().inflate(R.layout.client_dialog_input,null);
            final EditText input=view.findViewById(R.id.client_dialog_input);
            final TextView hint=view.findViewById(R.id.client_dialog_hint);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setText(archivePassword==null?"":archivePassword);
            input.setHint(R.string.client_archive_password_hint);
            hint.setText(wrong?"这个密码打不开 "+e.name+"，请重新输入":"这个压缩包是加密的，请输入解压密码");
            hint.setVisibility(View.VISIBLE);
            new MaterialAlertDialogBuilder(MainActivity.this).setTitle(R.string.client_archive_password).setView(view).setCancelable(false)
                .setNegativeButton(R.string.client_cancel,(d,w)->answered.countDown())
                .setPositiveButton("确定",(d,w)->{result[0]=input.getText().toString();answered.countDown();})
                .show();
        });
        try{answered.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return null;}
        return result[0];
    }
    /** 解压失败是不是「密码不对」那一类：各库在这里给的其实都是 CRC / 解密错误，
        跟真正的文件损坏没法区分，所以只重���三次就把原始错误抛回去，不替用户下结论。 */
    private static boolean looksLikePasswordTrouble(Throwable error){
        if(error==null)return false;
        /* 类型判定用 isInstance：release 包 R8 会把类名混淆成 a.b.c，getSimpleName() 不可靠。
           消息兜底保留（覆盖 zip4j 等未列出的库）。 */
        for(Throwable t=error;t!=null;t=t.getCause()){
            if(t instanceof org.apache.commons.compress.PasswordRequiredException
                    ||t instanceof com.github.junrar.exception.RarException
                    ||t instanceof net.lingala.zip4j.exception.ZipException
                    ||t instanceof java.util.zip.ZipException)return true;
        }
        String message=error.getMessage();if(message==null)return false;
        String lower=message.toLowerCase(Locale.ROOT);
        return lower.contains("wrong password")||lower.contains("password")||lower.contains("decrypt")||lower.contains("crc")||lower.contains("密码");
    }
    /** 查看压缩包内容：只列条目，不落盘也不解压。 */
    private void viewContents(Entry e){
        final File archive=new File(e.uri);
        if(!archive.isFile()){toast("无法读取该压缩包");return;}
        runArchiveJob("正在读取 "+archive.getName(),archive.getName(),progress->{
                List<ArchiveUtil.Item> items=ArchiveUtil.list(archive,archivePassword,archiveCharset);
                if(items.isEmpty())throw new IOException("这个压缩包里没有内容");
                final String[] lines=new String[items.size()];
                for(int i=0;i<items.size();i++){
                    ArchiveUtil.Item item=items.get(i);
                    String name=item.name==null||item.name.isEmpty()?"（未命名）":item.name;
                    lines[i]=name+(item.dir?"（文件夹）":item.size>0?"　"+Formatter.formatFileSize(MainActivity.this,item.size):"");
                }
                runOnUiThread(()->{if(isDestroyed()||isFinishing())return;
                    new MaterialAlertDialogBuilder(MainActivity.this).setTitle(archive.getName()+"　共 "+items.size()+" 项")
                        .setItems(lines,null).setPositiveButton("关闭",null).show();});
            },()->{},()->toast("已取消"));
    }
    /** 检查完整性：把每个条目读一遍让 CRC 校验说话，失败即「文件坏了或密码不对」。 */
    private void verifyArchive(Entry e){
        final File archive=new File(e.uri);
        if(!archive.isFile()){toast("无法读取该压缩包");return;}
        runArchiveJob("正在检查 "+archive.getName(),archive.getName(),
            progress->ArchiveUtil.verify(archive,progress,archivePassword,archiveCharset),
            ()->toast("压缩包完整，可以正常解压"),()->toast("已取消"));
    }
    private void archivePasswordDialog(){
        View view=getLayoutInflater().inflate(R.layout.client_dialog_input,null);
        final EditText input=view.findViewById(R.id.client_dialog_input);
        final TextView hint=view.findViewById(R.id.client_dialog_hint);
        input.setText(archivePassword==null?"":archivePassword);
        input.setHint(R.string.client_archive_password_hint);
        /* 提示行在布局里默认 gone，用之前必须显式打开，否则文案写了也看不见。 */
        hint.setText("密码用于 ZIP / 7Z / RAR 加密包；编码只影响 ZIP 文件名的解码方式。");
        hint.setVisibility(View.VISIBLE);
        final Spinner enc=new Spinner(this);
        String[] encNames={"文件名编码：自动","文件名编码：UTF-8","文件名编码：GBK","文件名编码：CP437"};
        final String[] encValues={"","UTF-8","GBK","CP437"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,encNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        enc.setAdapter(adapter);
        String current=archiveCharset==null?"":archiveCharset.name();
        for(int i=0;i<encValues.length;i++)if(encValues[i].equalsIgnoreCase(current)){enc.setSelection(i);break;}
        /* 整块 view 直接挂进 column：input/hint 已经是 view 的子控件，
           再 column.addView(input) 会抛 IllegalStateException（child already has a parent）——
           这就是点「密码」直接闪退的原因。column 不再另加左右 padding，view 自带 24dp。 */
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        column.addView(view);
        LinearLayout.LayoutParams encParams=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        encParams.leftMargin=dp(24);encParams.rightMargin=dp(24);encParams.topMargin=dp(10);
        column.addView(enc,encParams);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.client_archive_password).setView(column)
            .setNegativeButton("取消",null)
            .setPositiveButton("确定",(d,w)->{
                archivePassword=input.getText().toString();
                int pos=enc.getSelectedItemPosition();
                archiveCharset=pos<=0?null:java.nio.charset.Charset.forName(encValues[pos]);
                if(archivePassword.isEmpty())archivePassword=null;
                String status=(archivePassword==null?"无密码":"已设密码")+(archiveCharset==null?" / 自动编码":" / "+archiveCharset.name());
                toast(status);
            }).show();
    }
    /**
     * 解压到当前目录时的同名文件策略：第一个冲突弹窗问一次（覆盖／全部跳过／取消解压），
     * 之后的同名条目沿用同一选择，不再反复弹窗。以前这里直接抛「目标已有同名文件」，
     * 会让整个解压中断在半路、只留下一部分文件。
     */
    private ArchiveUtil.Conflict overwritePolicy(final java.util.concurrent.atomic.AtomicInteger skipped){
        ArchiveUtil.Conflict remembered=ArchiveUtil.rememberConflict(target->{
            final java.util.concurrent.CountDownLatch answered=new java.util.concurrent.CountDownLatch(1);
            final int[] choice={ArchiveUtil.Conflict.ABORT};
            runOnUiThread(()->{
                if(isDestroyed()||isFinishing()){answered.countDown();return;}
                new MaterialAlertDialogBuilder(MainActivity.this).setTitle("已有同名文件")
                    .setMessage(target.getName()+"\n继续解压将用它替换当前目录里已存在的文件。")
                    .setCancelable(false)
                    .setPositiveButton("全部覆盖",(d,w)->{choice[0]=ArchiveUtil.Conflict.OVERWRITE;answered.countDown();})
                    .setNegativeButton("全部跳过",(d,w)->{choice[0]=ArchiveUtil.Conflict.SKIP;answered.countDown();})
                    .setNeutralButton("取消解压",(d,w)->{choice[0]=ArchiveUtil.Conflict.ABORT;answered.countDown();})
                    .show();
            });
            try{answered.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return ArchiveUtil.Conflict.ABORT;}
            return choice[0];
        });
        return target->{
            if(archiveCancelled)return ArchiveUtil.Conflict.ABORT;
            int choice=remembered.resolve(target);
            if(choice==ArchiveUtil.Conflict.SKIP)skipped.incrementAndGet();
            if(choice==ArchiveUtil.Conflict.ABORT)archiveCancelled=true;
            return choice;
        };
    }
    /** 压缩：弹出格式 / 密码 / 名称对话框，然后调用 ArchiveUtil.createArchive。 */
    private void compress(ArrayList<Entry> chosen){
        if(chosen.isEmpty())return;
        ArrayList<File> inputs=new ArrayList<>();
        for(Entry e:chosen){if(!e.profileId.isEmpty()){warn("暂不支持压缩远程文件，请先下载到本地");return;}inputs.add(new File(e.uri));}
        File parent=inputs.get(0).getParentFile();if(parent==null){toast("无法确定目标目录");return;}
        final String base=chosen.size()==1?ArchiveUtil.stripExtension(inputs.get(0).getName()):"打包"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(new Date());
        final String[] formats={"zip","7z","tar","tar.gz","tar.bz2","tar.xz"};
        final String[] formatLabels={"ZIP","7Z","TAR","TAR.GZ","TAR.BZ2","TAR.XZ"};
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(24),dp(12),dp(24),0);
        TextView nameLabel=new TextView(this);nameLabel.setText(R.string.client_archive_name);nameLabel.setTextColor(attrColor(R.attr.client_text_secondary));
        EditText nameInput=new EditText(this);nameInput.setText(base);nameInput.setTextColor(attrColor(R.attr.client_text));nameInput.setHint(R.string.client_archive_name);
        TextView formatLabel=new TextView(this);formatLabel.setText(R.string.client_archive_format);formatLabel.setTextColor(attrColor(R.attr.client_text_secondary));formatLabel.setPadding(0,dp(12),0,0);
        RadioGroup formatGroup=new RadioGroup(this);
        for(int i=0;i<formats.length;i++){RadioButton rb=new RadioButton(this);rb.setText(formatLabels[i]);rb.setTag(formats[i]);rb.setTextColor(attrColor(R.attr.client_text));if(i==0)rb.setChecked(true);formatGroup.addView(rb);}
        TextView pwdLabel=new TextView(this);pwdLabel.setText(R.string.client_archive_password);pwdLabel.setTextColor(attrColor(R.attr.client_text_secondary));pwdLabel.setPadding(0,dp(12),0,0);
        EditText pwdInput=new EditText(this);pwdInput.setHint(R.string.client_archive_password_hint);pwdInput.setTextColor(attrColor(R.attr.client_text));
        formatGroup.setOnCheckedChangeListener((group,id)->{RadioButton selected=group.findViewById(id);boolean supported=selected!=null&&("zip".equals(selected.getTag())||"7z".equals(selected.getTag()));pwdInput.setEnabled(supported);pwdInput.setAlpha(supported?1f:.45f);pwdInput.setHint(supported?getString(R.string.client_archive_password_hint):"该格式不支持加密");});
        column.addView(nameLabel);column.addView(nameInput);column.addView(formatLabel);column.addView(formatGroup);column.addView(pwdLabel);column.addView(pwdInput);
        AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(R.string.client_compress).setView(column)
            .setNegativeButton(R.string.client_cancel,null).setPositiveButton("确定",null).create();
        dialog.setOnShowListener(d->{
            android.widget.Button positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v->{
                String name=nameInput.getText().toString().trim();
                if(name.isEmpty()){toast("请输入压缩包名称");return;}
                if(name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")||name.endsWith(".")){toast("压缩包名称不能包含路径分隔符、控制字符或末尾句点");return;}
                int checked=formatGroup.getCheckedRadioButtonId();
                RadioButton rb=formatGroup.findViewById(checked);
                String format=rb==null?"zip":(String)rb.getTag();
                String password=pwdInput.getText().toString();
                if(!format.equals("zip")&&!format.equals("7z"))password="";
                String ext=format;
                File out=new File(parent,name+"."+ext);int i=2;while(out.exists())out=new File(parent,name+" ("+(i++)+")."+ext);
                final File target=out;final String fmt=format;final String pwd=password.isEmpty()?null:password;
                dialog.dismiss();
                runArchiveJob("正在压缩 "+target.getName(),target.getName(),progress->ArchiveUtil.createArchive(inputs,target,fmt,progress,pwd),
                    ()->toast("已生成 "+target.getName()),()->toast("压缩已取消"));
            });
        });
        dialog.show();
    }
    /**
     * 解压/压缩统一执行：后台线程干活，界面显示可取消的进度弹窗，
     * 完成后刷新列表（失败时保留已生成的文件并提示原因）。
     */
    private void runArchiveJob(String title,String initial,ArchiveJob job,Runnable onDone,Runnable onCancelled){
        if(busy){toast("请等待当前操作完成");return;}busy=true;archiveCancelled=false;
        final TextView label=new TextView(this);label.setText(initial);label.setTextSize(13);label.setTextColor(attrColor(R.attr.client_text_secondary));
        label.setPadding(dp(24),dp(10),dp(24),dp(4));
        archiveDialog=new MaterialAlertDialogBuilder(this).setTitle(title).setView(label).setCancelable(false)
            .setNegativeButton("取消",(d,w)->archiveCancelled=true).create();
        archiveDialog.show();
        io.execute(()->{String failure=null;boolean cancelled=archiveCancelled;
            try{job.run(new ArchiveUtil.Progress(){
                    public void onProgress(final String name,final long written){runOnUiThread(()->{if(archiveDialog!=null&&archiveDialog.isShowing())label.setText(name+(written<0?"\n正在处理…":"\n已写入 "+Formatter.formatFileSize(MainActivity.this,written)));});}
                    public boolean cancelled(){return archiveCancelled;}});}
            /* 线程被中断也会走到 ArchiveUtil 的「已取消」，但那时 archiveCancelled 还是 false ——
               以前这段只看 archiveCancelled，于是半途中断的解压被当成成功、提示「已解压到 …」。
               现在把「已取消」本身也算作取消。 */
            catch(Exception e){boolean silent="已取消".equals(e.getMessage())||e instanceof InterruptedException;
                if(silent||archiveCancelled){cancelled=true;failure=null;}
                else failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            final boolean wasCancelled=cancelled||archiveCancelled;final String result=failure;
            runOnUiThread(()->{busy=false;if(archiveDialog!=null){archiveDialog.dismiss();archiveDialog=null;}
                if(isDestroyed())return;
                if(result!=null){message(title+"失败",result);}
                else if(wasCancelled)onCancelled.run();
                else onDone.run();
                load();});
        });
    }
    private interface ArchiveJob{void run(ArchiveUtil.Progress progress)throws Exception;}
    /** 「更多操作」菜单项构建（与点击分发拆开，SelectionToolbarUiTest 只反射调这个方法断言文案）。
        codes 与 items 一一对应。 */
    private java.util.List<MenuItem> buildSelectionItems(ArrayList<Entry> chosen,ArrayList<Integer> codes){
        final java.util.List<MenuItem> items=new ArrayList<>();
        boolean local=!chosen.isEmpty()&&chosen.get(0).profileId.isEmpty();
        boolean remote=!chosen.isEmpty()&&!chosen.get(0).profileId.isEmpty();
        /* 网络位置的文件：第一项就是「下载到系统 Download」。
           目标固定取主存储（机内 ROM）的公共下载目录，不受「默认目录」影响，也不会落到 TF 卡上。
           本地文件不出现这一项——本地下到「默认目录」（见 code 5），避免两个下载语义重叠。 */
        if(remote){codes.add(16);items.add(new MenuItem("下载到系统 Download 文件夹",R.drawable.client_ic_menu_download_dir));}
        if(chosen.size()==1&&isPlayable(chosen.get(0))){String play=isAudio(chosen.get(0))?"播放音频":"播放视频";codes.add(14);items.add(new MenuItem(play,R.drawable.client_ic_menu_play));}
        if(local&&chosen.size()==1&&!chosen.get(0).dir&&mimeOf(chosen.get(0)).startsWith("image/")&&!chosen.get(0).uri.startsWith("content:")){codes.add(10);items.add(new MenuItem("图片编辑",R.drawable.client_ic_menu_edit));}
        if(local&&chosen.size()==1&&!chosen.get(0).dir&&ArchiveUtil.isArchive(chosen.get(0).name)){
            codes.add(11);items.add(new MenuItem("解压到当前目录",R.drawable.client_ic_menu_extract));
            codes.add(12);items.add(new MenuItem("解压到新文件夹",R.drawable.client_ic_menu_extract_new));
            codes.add(15);items.add(new MenuItem("预设解压密码 / 文件名编码",R.drawable.client_ic_menu_password));}
        if(local&&!chosen.isEmpty()){codes.add(13);items.add(new MenuItem("压缩…",R.drawable.client_ic_menu_compress));}
        codes.add(0);items.add(new MenuItem("复制",R.drawable.client_ic_menu_copy));
        codes.add(1);items.add(new MenuItem("移动",R.drawable.client_ic_menu_move));
        codes.add(4);items.add(new MenuItem("全选",R.drawable.client_ic_menu_select_all));
        /* 本地文件落到「默认目录」其实是复制，不叫下载；远程文件才是下载语义。 */
        codes.add(5);items.add(new MenuItem(remote?"下载到默认目录":"拷贝到默认目录",remote?R.drawable.client_ic_menu_download_dir:R.drawable.client_ic_menu_copy));
        return items;
    }
    private void dispatchSelectionCode(ArrayList<Entry> chosen,int code){
        if(code==14){playMedia(chosen.get(0));return;}
        if(code==16){downloadToSystemDownloads(chosen);return;}
        if(code==10){editImage(chosen.get(0));return;}
        if(code==11){extract(chosen.get(0),false);return;}
        if(code==12){extract(chosen.get(0),true);return;}
        if(code==13){compress(chosen);return;}
        if(code==15){archivePasswordDialog();return;}
        if(code<2){for(Entry x:chosen)if(x.dir){warn("暂不支持递归复制或移动文件夹，请进入文件夹内选择文件");return;}clipboard.clear();clipboard.addAll(chosen);moveClipboard=code==1;clipboardSource=tab().current();clipboardProfile=tab().profileId;selected.clear();adapter.notifyDataSetChanged();updateActions();}
        else if(code==4){for(Entry x:adapter.visible())selected.add(x.uri);adapter.notifyDataSetChanged();updateActions();}else downloadDefault();
    }
    private void selectionMenu(View anchor){final ArrayList<Entry> chosen=selectedEntries();
        final ArrayList<Integer> codes=new ArrayList<>();
        java.util.List<MenuItem> items=buildSelectionItems(chosen,codes);
        showIconMenu(anchor,"更多操作",items,n->dispatchSelectionCode(chosen,codes.get(n)));}
    private void folderMenu(View anchor){PopupMenu menu=new PopupMenu(this,anchor);menu.getMenuInflater().inflate(R.menu.client_folder,menu.getMenu());android.view.MenuItem vm=menu.getMenu().findItem(R.id.client_action_view_mode);if(vm!=null)vm.setTitle(gridMode()?R.string.client_view_list:R.string.client_view_grid);menu.setOnMenuItemClickListener(i->{int id=i.getItemId();if(id==R.id.client_action_view_mode)toggleViewMode();else if(id==R.id.client_action_refresh)load();else if(id==R.id.client_action_new_folder)createFolder();else if(id==R.id.client_action_set_default){if(tab().remote()||tab().current().isEmpty())toast("默认接收目录必须是手机或 TF 卡上的本地目录");else{prefs.edit().putString("default_uri",tab().current()).putString("default_name",tab().title()).apply();refreshDefaultFolderMenu();toast("已设为默认目录");}}else message("目录属性",tab().remote()?tab().current():tab().current().isEmpty()?"尚未打开目录":tab().current());return true;});showFolderMenu(anchor,menu);}
    private PopupWindow folderPopup;
    /** 目录属性菜单：与「新标签页」「更多操作」共用同一套图标菜单实现，风格全局一致。 */
    private void showFolderMenu(View anchor,PopupMenu actions){
        android.view.Menu menu=actions.getMenu();
        android.view.MenuItem mode=menu.findItem(R.id.client_action_view_mode);
        mode.setIcon(gridMode()?R.drawable.client_menu_icon_list:R.drawable.client_menu_icon_grid);
        menu.findItem(R.id.client_action_properties).setIcon(R.drawable.client_menu_icon_properties);
        final java.util.List<android.view.MenuItem> visible=new ArrayList<>();
        for(int n=0;n<menu.size();n++){android.view.MenuItem item=menu.getItem(n);if(item.isVisible())visible.add(item);}
        if(menuPopup!=null)menuPopup.dismiss();
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(8),dp(8),dp(8),dp(8));
        for(int n=0;n<visible.size();n++){
            final int index=n;android.view.MenuItem item=visible.get(n);
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),0,dp(12),0);
            row.setBackgroundResource(R.drawable.client_folder_menu_row);row.setClickable(true);row.setFocusable(true);
            ImageView icon=new ImageView(this);icon.setImageDrawable(item.getIcon());icon.setImageTintList(ColorStateList.valueOf(attrColor(R.attr.client_text_secondary)));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));
            TextView label=new TextView(this);label.setText(item.getTitle());label.setTextSize(14);label.setTextColor(attrColor(R.attr.client_text));label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);textParams.setMarginStart(dp(14));row.addView(label,textParams);
            row.setOnClickListener(v->{popupDismiss();menu.performIdentifierAction(item.getItemId(),0);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48));p.bottomMargin=dp(2);
            column.addView(row,p);
        }
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.addView(column);
        int width=Math.min(dp(280),Math.max(dp(180),getWindow().getDecorView().getWidth()-dp(24)));
        int height=Math.min(dp(16+50*visible.size()),Math.max(dp(96),getWindow().getDecorView().getHeight()-dp(24)));
        final PopupWindow popup=new PopupWindow(scroll,width,height,true);folderPopup=popup;
        popup.setBackgroundDrawable(androidx.appcompat.content.res.AppCompatResources.getDrawable(this,R.drawable.client_folder_menu_background));
        popup.setElevation(dp(12));popup.setOutsideTouchable(true);popup.setClippingEnabled(true);
        popup.setOnDismissListener(()->{if(folderPopup==popup)folderPopup=null;});
        popup.showAsDropDown(anchor,0,dp(6),Gravity.END);
    }
    private interface NamedAction {void run(String name);}
    /** 命名弹窗：统一圆角外观，输入非法时保留弹窗并就地提示。 */
    private void nameDialog(String title,String initial,NamedAction call){View view=getLayoutInflater().inflate(R.layout.client_dialog_input,null);
        final EditText input=view.findViewById(R.id.client_dialog_input);final TextView hint=view.findViewById(R.id.client_dialog_hint);
        input.setText(initial==null?"":initial);if(initial!=null&&!initial.isEmpty())input.setSelection(0,initial.length());if(initial==null||initial.isEmpty())input.setHint("请输入名称");
        final AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(title).setView(view).setNegativeButton("取消",null).setPositiveButton("确定",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=input.getText().toString().trim();
            String problem=name.isEmpty()?"请输入名称":(name.contains("/")||name.equals(".")||name.equals(".."))?"名称不能包含 / ，也不能是 . 或 ..":"";
            if(!problem.isEmpty()){hint.setText(problem);hint.setVisibility(View.VISIBLE);return;}
            dialog.dismiss();call.run(name);}));
        dialog.show();input.requestFocus();}
    private interface Job {void run() throws Exception;}
    /** 失败文案兜底：有些异常（NPE 等）getMessage() 是 null，直接拿 null 当"成功"会把失败报成完成。 */
    static String reason(Throwable error){String message=error==null?null:error.getMessage();return message==null||message.trim().isEmpty()?"未知原因（"+error.getClass().getSimpleName()+"）":message;}
    /** 文件操作的进行中弹窗统一登记：job()/externalFull() 完成后自行 dismiss，
        但 MainActivity 旋转时会销毁重建（manifest 未声明 configChanges）——
        不跟踪引用的话，销毁瞬间处于 showing 的弹窗就是 WindowLeaked，
        且 io 回调 lambda 捕获了对话框 + this，旧实例要留到操作跑完才释放。 */
    private final java.util.List<android.app.ProgressDialog> operationDialogs=new java.util.ArrayList<>();
    private android.app.ProgressDialog fileOperationDialog(String title){
        android.app.ProgressDialog dialog=new android.app.ProgressDialog(this);
        dialog.setTitle(title);dialog.setMessage("正在处理，请稍候…");
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        dialog.setIndeterminate(true);dialog.setCancelable(false);dialog.show();
        operationDialogs.add(dialog);return dialog;
    }
    private void dismissOperationDialog(android.app.ProgressDialog dialog){
        if(dialog==null)return;
        operationDialogs.remove(dialog);
        try{if(dialog.isShowing())dialog.dismiss();}catch(Throwable ignored){}
    }
    private void job(String title,Job work){if(busy){toast("请等待当前文件操作完成");return;}busy=true;lastTask=title+"…";android.app.ProgressDialog operation=fileOperationDialog(title);io.execute(()->{String failure=null;try{work.run();}catch(Exception e){failure=reason(e);}final String error=failure;runOnUiThread(()->{busy=false;dismissOperationDialog(operation);if(isDestroyed())return;lastTask=error==null?title+"完成":title+"失败："+error;if(error!=null)message("文件操作失败",lastTask);else toast(lastTask);load();});});}
    private void createFolder(){if(tab().remote()){remoteMkdir();return;}if(tab().current().isEmpty()){toast("请先打开目录");return;}File parent=new File(tab().current());nameDialog("新建文件夹","",n->job("新建文件夹",()->{if(!new File(parent,n).mkdirs())throw new IOException("创建失败（可能已存在同名目录）");}));}
    /** 底栏「新建」：先选类型，再弹窗输入名称。 */
    private void newItemMenu(){if(photoFavoritesTab()){toast("请先切换到文件目录");return;}if(!tab().remote()&&tab().current().isEmpty()){toast("请先打开目录");return;}
        new MaterialAlertDialogBuilder(this).setTitle("新建").setItems(new String[]{"新建文件夹","新建文件"},(d,n)->{if(n==0)createFolder();else createFile();}).show();}
    private void createFile(){if(tab().remote()){toast("远程端暂不支持新建文件");return;}if(tab().current().isEmpty()){toast("请先打开目录");return;}File parent=new File(tab().current());nameDialog("新建文件","",n->job("新建文件",()->{if(!new File(parent,n).createNewFile())throw new IOException("已存在同名文件，或创建失败");}));}
    /* ===== 目录收藏：☆ 收藏 / ★ 已收藏，收藏夹页面一键直达 ===== */
    static boolean favoriteMatches(JSONObject o,String uri,String profileId){return o!=null&&uri.equals(o.optString("uri"))&&profileId.equals(o.optString("profileId"));}
    private String favoritesJson(){return prefs.getString("favorites","[]");}
    private JSONArray favorites(){try{return new JSONArray(favoritesJson());}catch(JSONException e){return new JSONArray();}}
    private boolean isFavoriteDir(String uri){if(uri==null||uri.isEmpty())return false;JSONArray a=favorites();for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(favoriteMatches(o,uri,tab().profileId))return true;}return false;}
    private void updateFavoriteIcon(){if(favoriteIcon==null)return;
        boolean ready=!tabs.isEmpty()&&(tab().remote()||!tab().current().isEmpty());boolean marked=ready&&isFavoriteDir(tab().current());
        favoriteIcon.setImageResource(marked?R.drawable.client_bottom_icon_favorite_filled:R.drawable.client_bottom_icon_favorite);
        favoriteIcon.setImageTintList(ColorStateList.valueOf(marked?attrColor(R.attr.client_accent):attrColor(R.attr.client_text)));
        if(favoriteButton!=null)favoriteButton.setAlpha(ready?1f:0.45f);}
    private void toggleFavoriteDir(){if(photoFavoritesTab())return;if(tabs.isEmpty()||(!tab().remote()&&tab().current().isEmpty())){toast("请先打开一个目录");return;}
        String uri=tab().current();JSONArray a=favorites();int found=-1;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(favoriteMatches(o,uri,tab().profileId)){found=i;break;}}
        try{
            if(found>=0){a.remove(found);prefs.edit().putString("favorites",a.toString()).apply();toast("已取消收藏");}
            else{if(a.length()>=50){warn("收藏数量已达上限（50）\n请先移除部分收藏");return;}
                JSONObject o=new JSONObject();o.put("uri",uri);o.put("name",tab().title());o.put("profileId",tab().profileId);a.put(o);prefs.edit().putString("favorites",a.toString()).apply();toast("已收藏："+tab().title());}
        }catch(JSONException ignored){}
        updateFavoriteIcon();
        if("已收藏的目录".equals(currentPage))reopenPage(favoritesPage());}
    private void removeFavoriteDialog(String uri,String name,String profileId){new MaterialAlertDialogBuilder(this).setTitle("移除收藏？").setMessage(name).setPositiveButton("移除",(d,w)->{JSONArray a=favorites();JSONArray next=new JSONArray();for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!favoriteMatches(o,uri,profileId))next.put(o);}prefs.edit().putString("favorites",next.toString()).apply();updateFavoriteIcon();reopenPage(favoritesPage());}).setNegativeButton("取消",null).show();}
    private void openFavorite(String uri,String name,String profileId){
        if(profileId.isEmpty()){
            if(!canNewTab())return;
            if(!fullStorageGranted()){requestFullAccess();return;}
            closePageAndDrawer();openFullStorage(uri,true);return;
        }
        final ConnectionStore.Profile p;
        try{p=connections.get(profileId);}catch(Exception e){error(e);return;}
        if(p==null){warn("该收藏对应的连接配置已不存在");return;}
        if(!canNewRemoteTab())return;closePageAndDrawer();
        /* 与打开网络位置同一套逻辑：先把标签页切过去，连接和列目录都在后台跑。 */
        TabState t=new TabState();t.profileId=profileId;t.label=name;t.stack.add(new Place(p.root,p.name));t.stack.add(new Place(uri,name));
        tabs.add(t);active=tabs.size()-1;updateFavoriteIcon();selected.clear();search.setText("");entries.clear();
        renderTabs();load();
    }
    private void rename(){ArrayList<Entry> a=selectedEntries();if(a.size()!=1){toast("请选择一个项目");return;}Entry x=a.get(0);if(!x.profileId.isEmpty()){remoteRename(x);return;}File src=new File(x.uri);nameDialog("重命名",x.name,n->job("重命名",()->{SafeFileOperations.renameNew(src,new File(src.getParentFile(),n));}));}
    private void delete(){ArrayList<Entry> chosen=selectedEntries();if(tab().remote()){remoteDelete(chosen);return;}new MaterialAlertDialogBuilder(this).setTitle("删除 "+chosen.size()+" 个项目？").setMessage("将删除真实文件，无法在本应用中撤销。文件夹将连同内容一起删除。").setPositiveButton("删除",(d,w)->job("删除",()->{for(Entry x:chosen){File f=new File(x.uri);if(f.isDirectory()){if(!deleteRecursively(f))throw new IOException("无法删除 "+x.name);}else if(!f.delete()&&f.exists())throw new IOException("无法删除 "+x.name);}})).setNegativeButton("取消",null).show();}
    private boolean deleteRecursively(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)if(!deleteRecursively(c))return false;return f.delete();}
    private void ensureNameAvailable(File parent,String name)throws IOException{if(new File(parent,name).exists())throw new IOException("目标已有同名文件："+name+"，原文件未覆盖");}
    private void paste(){if(photoFavoritesTab()){toast("请先选择目标目录");return;}if(tab().remote()||!clipboardProfile.isEmpty()){pasteNetwork();return;}if(tab().current().isEmpty()){toast("请先选择目标目录");return;}if(tab().current().equals(clipboardSource)){toast("请选择不同的目录");return;}File parent=new File(tab().current());ArrayList<Entry> files=new ArrayList<>(clipboard);boolean moving=moveClipboard;if(busy){toast("请等待当前操作完成");return;}clipboard.clear();updateActions();job(moving?"移动文件":"复制文件",()->{for(Entry x:files){ensureNameAvailable(parent,x.name);File src=new File(x.uri),dest=new File(parent,x.name);if(moving){if(!src.renameTo(dest))throw new IOException("无法移动 "+x.name);}else{
            /* 复制也走「临时文件 + 原子落位」：直接 new FileOutputStream(dest) 的话，
               大文件复制途中进程被杀 / 断电会在目标位置留下无后缀的半成品，
               和正常文件无法区分。writeNew 先写同目录 .cjfm-*.tmp 再 createLink/CREATE_NEW 提交。 */
            try{SafeFileOperations.writeNew(dest,out->{try(InputStream in=new FileInputStream(src)){byte[] buffer=new byte[128*1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}out.flush();});}
            catch(Exception e){throw new IOException("复制失败："+x.name+"（"+reason(e)+"）",e);}
        }}});}
    private void external(ArrayList<Entry> files,boolean open){if(!files.isEmpty()&&!files.get(0).profileId.isEmpty()){remoteExternal(files,open);return;}if(!files.isEmpty()&&"FULL".equals(tab().tree)&&!files.get(0).uri.startsWith("content:")){externalFull(files,open);return;}if(files.isEmpty()||(open&&files.size()!=1)){toast("打开方式需要选择一个文件");return;}for(Entry e:files)if(e.dir){toast("请选择文件；目录暂不支持分享或打开");return;}ArrayList<Uri> uris=new ArrayList<>();String mime=files.get(0).mime;for(Entry e:files){uris.add(Uri.parse(e.uri));if(!mime.equals(e.mime))mime=mime.split("/")[0].equals(e.mime.split("/")[0])?mime.split("/")[0]+"/*":"*/*";}Intent intent=new Intent(open?Intent.ACTION_VIEW:files.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE);if(open)intent.setDataAndType(uris.get(0),mime);else{intent.setType(mime);if(files.size()==1)intent.putExtra(Intent.EXTRA_STREAM,uris.get(0));else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);}ClipData data=new ClipData("files",new String[]{mime},new ClipData.Item(uris.get(0)));for(int i=1;i<uris.size();i++)data.addItem(new ClipData.Item(uris.get(i)));intent.setClipData(data);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(Intent.createChooser(intent,open?"用其他应用打开":"分享文件"));}catch(ActivityNotFoundException e){toast("没有可处理此文件的应用");}catch(SecurityException e){error(e);}}
    private void externalFull(ArrayList<Entry> files,boolean open){if(open&&files.size()!=1){toast("打开方式需要选择一个文件");return;}android.app.ProgressDialog operation=fileOperationDialog("缓存文件");File directory=new File(getCacheDir(),"client-exports/"+UUID.randomUUID());io.execute(()->{ArrayList<Entry> prepared=new ArrayList<>();try{if(!directory.mkdirs())throw new IOException("无法创建临时缓存");for(Entry file:files){File out=new File(directory,file.name);try(InputStream in=new FileInputStream(file.uri);OutputStream stream=new FileOutputStream(out)){byte[] buffer=new byte[128*1024];int n;while((n=in.read(buffer))!=-1)stream.write(buffer,0,n);}Entry local=new Entry();local.uri=FileProvider.getUriForFile(this,getPackageName()+".client.exports",out).toString();local.name=file.name;local.mime=file.mime;prepared.add(local);}runOnUiThread(()->{dismissOperationDialog(operation);if(!isDestroyed())external(prepared,open);});}catch(Exception e){File[] leftovers=directory.listFiles();if(leftovers!=null)for(File f:leftovers)f.delete();directory.delete();runOnUiThread(()->{dismissOperationDialog(operation);if(!isDestroyed())error(e);});}});}
    private float gestureX,gestureY; private long gestureTime; private boolean gestureCandidate,gestureConsumed,sidebarOutsideTouch;
    private boolean sidebarSwipeCandidate,sidebarSwiping;
    private boolean sidebarSwipeSettling,sidebarDragPage;
    private View sidebarDragView;
    private View sidebarPressedButton;
    private float sidebarDownX,sidebarDownY;private long sidebarDownAt;
    private final java.util.Map<String,Rect> sidebarSwipeExclusions=new java.util.HashMap<>();
    void pageExcludeSidebarSwipe(String key,Rect bounds){sidebarSwipeExclusions.put(key,new Rect(bounds));}
    private boolean sidebarInputAt(View view,int x,int y){
        Rect rect=new Rect();if(view.getVisibility()!=View.VISIBLE||!view.getGlobalVisibleRect(rect)||!rect.contains(x,y))return false;
        if(view instanceof EditText||view instanceof SeekBar||view instanceof com.google.android.material.slider.Slider)return true;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=group.getChildCount()-1;i>=0;i--)if(sidebarInputAt(group.getChildAt(i),x,y))return true;}return false;
    }
    private boolean dispatchSidebarSwipe(MotionEvent event){
        int action=event.getActionMasked();View host=findViewById(R.id.client_sidebar_host);
        if(sidebarSwipeSettling)return true;
        if(action==MotionEvent.ACTION_DOWN){
            Rect bounds=new Rect();host.getGlobalVisibleRect(bounds);
            if(!bounds.contains((int)event.getRawX(),(int)event.getRawY())){sidebarOutsideTouch=true;return true;}
            sidebarDownX=event.getRawX();sidebarDownY=event.getRawY();sidebarDownAt=event.getEventTime();
            sidebarSwiping=false;sidebarSwipeCandidate=!sidebarInputAt(host,(int)sidebarDownX,(int)sidebarDownY);
            for(Rect exclusion:sidebarSwipeExclusions.values())if(exclusion.contains((int)sidebarDownX,(int)sidebarDownY))sidebarSwipeCandidate=false;
            if(sidebarSwipeCandidate){sidebarPressedButton=SidebarPressMotion.target(host,(int)sidebarDownX,(int)sidebarDownY);
                if(sidebarPressedButton==host)sidebarPressedButton=null;
                if(sidebarPressedButton!=null)SidebarPressMotion.down(sidebarPressedButton);
            }
        }
        if(event.getPointerCount()>1){sidebarSwipeCandidate=false;if(sidebarSwiping){settleSidebarSwipe(host,false);return true;}}
        float dx=event.getRawX()-sidebarDownX,dy=event.getRawY()-sidebarDownY;
        if(action==MotionEvent.ACTION_CANCEL||event.getPointerCount()>1||action==MotionEvent.ACTION_MOVE&&(Math.abs(dx)>dp(12)||Math.abs(dy)>dp(12))){SidebarPressMotion.release(sidebarPressedButton,true);sidebarPressedButton=null;}
        else if(action==MotionEvent.ACTION_UP){SidebarPressMotion.release(sidebarPressedButton,false);sidebarPressedButton=null;}
        if(action==MotionEvent.ACTION_MOVE&&sidebarSwipeCandidate&&!sidebarSwiping){
            if(event.getEventTime()-sidebarDownAt>1500||Math.abs(dy)>dp(12)&&Math.abs(dy)>Math.abs(dx)*1.2f)sidebarSwipeCandidate=false;
            else if(dx < -dp(18)&&-dx>Math.abs(dy)*1.8f){
                sidebarSwiping=true;MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();
                sidebarDragPage=sidebarPageOpen();sidebarDragView=sidebarDragPage?sidebarPage:host;
                sidebarDragView.animate().cancel();if(sidebarDragPage){sidebarNav.setAlpha(1f);sidebarNav.setVisibility(View.VISIBLE);}
            }
        }
        if(sidebarSwiping){
            /* 拖动中只改变透明度做即时反馈，不横向平移——用户明确不要「内容跟着手指向左滑出」的效果。 */
            if(action==MotionEvent.ACTION_MOVE){
                float width=Math.max(1,host.getWidth());
                float progress=Math.max(0f,Math.min(1f,-dx/width));
                sidebarDragView.setAlpha(1f-progress*0.7f);
                if(!sidebarDragPage)drawer.setScrimColor(android.graphics.Color.argb(Math.round(153*(1-progress)),0,0,0));
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                float threshold=dp(64);
                boolean commit=action==MotionEvent.ACTION_UP&&-dx>=threshold&&-dx>Math.abs(dy)*1.6f&&event.getEventTime()-sidebarDownAt<=1500;
                settleSidebarSwipe(host,commit);
            }return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)sidebarSwipeCandidate=false;
        return super.dispatchTouchEvent(event);
    }
    private void settleSidebarSwipe(View host,boolean commit){
        sidebarSwiping=false;sidebarSwipeCandidate=false;sidebarSwipeSettling=true;
        View moving=sidebarDragView;boolean page=sidebarDragPage;
        if(moving==null){sidebarSwipeSettling=false;return;}
        /* 退出一律走透明度渐变，不再有任何横向位移：
           二级页提交退出＝渐出后收页；抽屉层提交退出＝渐出后关抽屉；未达阈值＝淡回原状。
           以前这里用 translationX 把内容滑出屏幕，用户明确要求改掉。 */
        if(commit){
            moving.animate().cancel();
            moving.animate().alpha(0f).setDuration(140).setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction(()->{
                    moving.setAlpha(1f);drawer.setScrimColor(0x99000000);
                    sidebarDragView=null;sidebarSwipeSettling=false;
                    if(isFinishing()||isDestroyed())return;
                    if(page)closePageImmediate();
                    else drawer.closeDrawer(androidx.core.view.GravityCompat.START,false);
                }).start();
            return;
        }
        moving.animate().cancel();
        moving.animate().alpha(1f).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
            .withEndAction(()->{
                if(!page){drawer.setScrimColor(0x99000000);}
                else if(sidebarPageOpen())settleSidebarLayer(true);
                moving.setAlpha(1f);sidebarDragView=null;sidebarSwipeSettling=false;
            }).start();
    }
    private void installSwipe() {
        // Disable DrawerLayout edge dragging; explicit bottom-button openDrawer calls still work.
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED,androidx.core.view.GravityCompat.START);
    }
    /** 左右滑动列表区：右滑=上一个标签页，左滑=下一个标签页（等价于切换当前浏览的目录）。 */
    private boolean tabFading;
    /** 在旧目录仍可见时先记住滚动位置，再切换数据源；供手势与顶部标签点击共同使用。 */
    private void activateTab(int target){
        saveScroll();active=target;selected.clear();search.setText(tab().query);loadTab();
    }
    private void switchTabWithFade(int target){
        if(target<0||target>=tabs.size()||target==active)return;
        list.animate().cancel();list.animate().alpha(0f).setDuration(110).withEndAction(()->{
            if(isDestroyed()||isFinishing()){list.setAlpha(1f);return;}
            activateTab(target);list.setAlpha(0f);list.animate().alpha(1f).setDuration(170).start();
        }).start();
    }
    private void switchTabRelative(int delta){
        if(tabs.size()<2){toast("当前只有一个标签页");return;}
        int target=active+delta;
        if(target<0||target>=tabs.size()){toast(delta<0?"已经是第一个标签页":"已经是最后一个标签页");return;}
        // 淡出 → 切换 → 淡入。网络位置可能需要重新登录，切换前先让旧内容淡出，
        // 这样哪怕内容要等一会儿才到，界面也有明确的过渡而不是僵在原地。
        list.animate().cancel();
        list.animate().alpha(0f).setDuration(110).withEndAction(()->{
            if(isDestroyed()||isFinishing()){list.setAlpha(1f);return;}
            TabLayout.Tab t=tabBar.getTabAt(target);
            tabFading=t!=null;
            if(t!=null)t.select();else activateTab(target);
            tabFading=false;
            list.setAlpha(0f);
            list.animate().alpha(1f).setDuration(170).start();
        }).start();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (list == null || drawer == null) return super.dispatchTouchEvent(e);
        if(sidebarOutsideTouch){
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)sidebarOutsideTouch=false;
            return true;
        }
        if(drawer.isDrawerVisible(androidx.core.view.GravityCompat.START)||sidebarSwiping)return dispatchSidebarSwipe(e);
        if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            gestureConsumed=false;
            if(pulling){pulling=false;hideRefreshPills();}   /* 上一次拖动没收到 UP：先把指示器收干净 */
            pullDirection=0;pullDistance=0;pullBase=0; gestureX=e.getX(); gestureY=e.getY(); gestureTime=e.getEventTime();
            int[] pos=new int[2]; list.getLocationOnScreen(pos);
            WindowInsetsCompat in=ViewCompat.getRootWindowInsets(list);
            int leftEdge=in==null?0:in.getSystemGestureInsets().left;
            int rightEdge=in==null?0:in.getSystemGestureInsets().right;
            gestureCandidate=!drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)&&selected.isEmpty()&&!sidebarPageOpen()
                &&e.getRawX()>pos[0]+leftEdge&&e.getRawX()<pos[0]+list.getWidth()-rightEdge
                &&e.getRawY()>pos[1]&&e.getRawY()<pos[1]+list.getHeight()-dp(24);
        } else if(e.getActionMasked()==MotionEvent.ACTION_MOVE && gestureCandidate) {
            float dx=e.getX()-gestureX,dy=e.getY()-gestureY;
            if(pulling){updatePull(dy);return true;}
            if(Math.abs(dy)>dp(18)||e.getEventTime()-gestureTime>450){
                /* 竖直方向：列表已经贴到边界、用户还在往那个方向拖，就接管为刷新手势
                   （顶部继续下拉 / 列表底部继续上拉），否则放手让列表自己滚。 */
                if(!refreshing&&Math.abs(dy)>Math.abs(dx)*1.4f){
                    boolean atTop=!list.canScrollVertically(-1),atBottom=!list.canScrollVertically(1);
                    int direction=dy>0&&atTop?1:(dy<0&&atBottom?-1:0);
                    if(direction!=0){
                        pulling=true;pullDirection=direction;pullBase=dy;gestureConsumed=true;
                        MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();
                        updatePull(dy);return true;
                    }
                }
                gestureCandidate=false;
            }
            else if(Math.abs(dx)>dp(64)&&Math.abs(dx)>Math.abs(dy)*2) {
                gestureCandidate=false;gestureConsumed=true;
                MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();
                switchTabRelative(dx>0?-1:1);return true;
            }
        }
        if(gestureConsumed){
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                gestureConsumed=false;
                if(!pulling){/* 切换标签页：什么都不用收 */}
                else if(e.getActionMasked()==MotionEvent.ACTION_UP)finishPull();
                else {pulling=false;pullDirection=0;pullDistance=0;hideRefreshPills();}
            }
            return true;
        }
        return super.dispatchTouchEvent(e);
    }
    private AlertDialog storagePermissionDialog;
    private void requestFullAccess(){
        if(isFinishing()||isDestroyed()||(storagePermissionDialog!=null&&storagePermissionDialog.isShowing()))return;
        if(Build.VERSION.SDK_INT==29&&fullStorageGranted()){toast("Android 10 存储权限已允许");return;}
        storagePermissionDialog=new MaterialAlertDialogBuilder(this).setTitle("缺少存储权限")
                .setNegativeButton("暂不授权",null)
                .setPositiveButton("去授权",(dialog,which)->launchStoragePermission())
                .setOnDismissListener(dialog->storagePermissionDialog=null).show();
    }
    private void launchStoragePermission(){
        if(isFinishing()||isDestroyed())return;
        if(Build.VERSION.SDK_INT==29){requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,android.Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_STORAGE);return;}
        try{startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));}
        catch(ActivityNotFoundException first){try{startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}
            catch(ActivityNotFoundException second){toast("请在系统设置中允许本应用访问所有文件");}}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==REQUEST_STORAGE){boolean ok=results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED;if(ok)toast("存储权限已允许");else warn("缺少存储权限");}}
    @Override protected void onResume(){super.onResume();MusicPlaybackService.enterInterface(false);if(photoFavoritesTab())loadInternal(true,false);musicUi.removeCallbacks(musicTick);musicUi.post(musicTick);if("设置".equals(currentPage))reopenPage(settingsPage());View header=findViewById(R.id.client_storage_cards);if(header!=null)updateSdHeader(header);if(!tabs.isEmpty()&&!tab().remote()&&!tab().current().isEmpty()&&fullStorageGranted()&&entries.isEmpty())load();}
    @Override protected void onPause(){musicUi.removeCallbacks(musicTick);super.onPause();}
    /* ===== 侧边栏二级页面：侧边栏功能一律在抽屉内展示，不再用弹窗 ===== */
    /* currentPage 一起判断：淡出动画期间 sidebarPage 还是 VISIBLE，只靠可见性会在收起的那 190ms 里
       被当成「还开着」，返回键连点就会出错。 */
    private boolean sidebarPageOpen(){return sidebarPage!=null&&sidebarPage.getVisibility()==View.VISIBLE&&!currentPage.isEmpty();}
    /** 侧边栏主菜单 ↔ 二级页：交叉淡化，不做横向位移。
        两层都是 start 抽屉、同尺寸重叠，淡入淡出期间同时可见也不会互相挤位。 */
    private void crossfadeSidebar(boolean toPage){
        if(sidebarPage==null||sidebarNav==null)return;
        sidebarPage.animate().cancel();sidebarNav.animate().cancel();
        View incoming=toPage?sidebarPage:sidebarNav,outgoing=toPage?sidebarNav:sidebarPage;
        incoming.setVisibility(View.VISIBLE);incoming.setAlpha(0f);
        incoming.animate().alpha(1f).setDuration(170).setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f)).start();
        outgoing.animate().alpha(0f).setDuration(140).setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction(()->{outgoing.setVisibility(View.GONE);outgoing.setAlpha(1f);}).start();
    }
    /** 不带动画地把某一层摆到位：拖拽手势收尾、二级页原地重建时用。 */
    private void settleSidebarLayer(boolean page){
        if(sidebarPage==null||sidebarNav==null)return;
        sidebarPage.animate().cancel();sidebarNav.animate().cancel();
        sidebarPage.setAlpha(1f);sidebarNav.setAlpha(1f);
        sidebarPage.setVisibility(page?View.VISIBLE:View.GONE);
        sidebarNav.setVisibility(page?View.GONE:View.VISIBLE);
    }
    private void openPage(String title,View body){if(body==null||sidebarPage==null)return;
        sidebarSwipeExclusions.clear();
        /* 同一个二级页原地重建（改个设置项就会 reopen 一次）：内容直接换掉、不淡入，
           否则每动一下开关整页都要闪一次。换成另一个二级页（页→页导航）才走内容交叉淡化。 */
        boolean replacing=sidebarPage.getVisibility()==View.VISIBLE;
        boolean samePage=replacing&&title.equals(currentPage);
        View outgoing=samePage?null:(replacing&&sidebarPageBody.getChildCount()>0?sidebarPageBody.getChildAt(0):null);
        sidebarPageBody.removeAllViews();taskText=null;appearanceDim=null;appearanceAlpha=null;currentPage=title;sidebarPageTitle.setText(title);
        sidebarPageBody.addView(body,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        syncEditButton(body instanceof DownloadSidebarPage);
        syncPageAction(body instanceof DownloadSidebarPage?(DownloadSidebarPage)body:null);
        sidebarPage.post(this::insetBackButton);
        if(outgoing!=null){
            /* 页→页：旧内容淡出、新内容淡入；容器与层级不动，主菜单保持隐藏。
               outgoing 从当前透明度开始淡出——它自己可能正淡入（连续导航），不重置就不会闪。 */
            sidebarPageBody.addView(outgoing,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
            settleSidebarLayer(true);
            outgoing.animate().cancel();
            body.animate().cancel();body.setAlpha(0f);
            outgoing.animate().alpha(0f).setDuration(140).setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f)).withEndAction(()->sidebarPageBody.removeView(outgoing)).start();
            body.animate().alpha(1f).setDuration(170).setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f)).start();
        }
        else if(replacing)settleSidebarLayer(true);else crossfadeSidebar(true);
        if(!drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)){pendingPageOpen=true;drawer.openDrawer(androidx.core.view.GravityCompat.START);}}
    private void closePage(){
        if(sidebarPage==null)return;
        /* 滑动收尾进行中：退出由 settle 的结束动作统一渐出，别叠两套退出动画。 */
        if(sidebarSwipeSettling)return;
        sidebarSwipeExclusions.clear();syncEditButton(false);syncPageAction(null);taskText=null;appearanceDim=null;appearanceAlpha=null;currentPage="";
        if(sidebarPage.getVisibility()!=View.VISIBLE){finishClosePage();return;}
        crossfadeSidebar(false);
        /* 内容要等淡出结束再清：立刻 removeAllViews 的话，看到的是一张空白页在淡出。 */
        sidebarPage.postDelayed(this::finishClosePage,190);
    }
    /** 无动画收尾：滑动提交的渐出结束后调用——页面已经淡没，只剩清理。 */
    private void closePageImmediate(){
        if(sidebarPage==null)return;
        sidebarSwipeExclusions.clear();syncEditButton(false);syncPageAction(null);taskText=null;appearanceDim=null;appearanceAlpha=null;currentPage="";
        finishClosePage();
    }
    private void finishClosePage(){
        /* 淡出这 190ms 里又开了别的二级页就别收：内容已经被 openPage 换掉了。 */
        if(sidebarPage==null||!currentPage.isEmpty())return;
        sidebarPageBody.removeAllViews();sidebarPageBody.setPadding(0,0,0,dp(8));
        settleSidebarLayer(false);
    }
    /** 标题栏右侧的图标按钮：各子页面按需挂自己的动作（目前只有下载页的「添加下载」）。 */
    private void syncPageAction(DownloadSidebarPage page){
        ImageButton action=findViewById(R.id.client_page_add);if(action==null)return;
        int want=page==null?View.GONE:View.VISIBLE;
        action.setVisibility(want);
        action.setOnClickListener(page==null?null:v->page.addDownload());
    }
    /** 左下角「编辑」浮动按钮只在下载页出现；从下载设置子页返回时恢复。 */
    private void syncEditButton(boolean downloadPage){
        View edit=findViewById(R.id.client_page_edit);if(edit==null)return;
        int want=downloadPage&&"磁力／ed2k 下载".equals(currentPage)?View.VISIBLE:View.GONE;
        if(edit.getVisibility()!=want){edit.setVisibility(want);if(want==View.VISIBLE){edit.bringToFront();edit.invalidate();}}
    }
    private void fitSidebarWidth(){
        int panelWidth=Math.min(getResources().getDimensionPixelSize(R.dimen.client_drawer_width),Math.max(dp(144),drawer.getWidth()-dp(84)));
        View host=findViewById(R.id.client_sidebar_host);ViewGroup.LayoutParams hostParams=host.getLayoutParams();
        if(hostParams.width!=panelWidth){hostParams.width=panelWidth;host.setLayoutParams(hostParams);}
        /* 存储卡片那一列和菜单、二级页面同宽：小屏收窄时三者一起收，卡片不会比菜单宽出一截。 */
        View stack=findViewById(R.id.client_sidebar_stack);
        for(View panel:new View[]{stack,sidebarNav,sidebarPage}){if(panel==null)continue;ViewGroup.LayoutParams params=panel.getLayoutParams();if(params.width!=panelWidth){params.width=panelWidth;panel.setLayoutParams(params);}}
        insetBackButton();
    }
    /** Only the download editing action still needs bottom space. */
    private void insetBackButton(){
        if(sidebarNav==null||sidebarPageBody==null)return;
        View edit=findViewById(R.id.client_page_edit);
        applyScrollInset(sidebarNav,dp(12));
        applyScrollInset(sidebarPageBody,edit!=null&&edit.getVisibility()==View.VISIBLE?dp(80):dp(8));
    }
    /** 按最终位置改下边距（FrameLayout.LayoutParams），不动 translation。 */
    private static void setBottomMargin(View view,int margin){
        ViewGroup.LayoutParams params=view.getLayoutParams();
        if(!(params instanceof FrameLayout.LayoutParams))return;
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)params;
        if(lp.bottomMargin==margin)return;
        lp.bottomMargin=margin;view.setLayoutParams(lp);
    }
    /** 底部留白下放进内部滚动列表自身（clipToPadding=false）：内容能滚过返回键区域，
        只有滚动到终点时最后一项才让位——小屏不会再有大块够不着的无效空间。 */
    private void applyScrollInset(ViewGroup container,int inset){
        for(int i=0;i<container.getChildCount();i++){
            View c=container.getChildAt(i);
            if(c instanceof androidx.recyclerview.widget.RecyclerView||c instanceof android.widget.ScrollView){
                ViewGroup list=(ViewGroup)c;
                if(container.getPaddingBottom()!=0)container.setPadding(container.getPaddingLeft(),container.getPaddingTop(),container.getPaddingRight(),0);
                list.setClipToPadding(false);
                if(list.getPaddingBottom()!=inset)list.setPadding(list.getPaddingLeft(),list.getPaddingTop(),list.getPaddingRight(),inset);
                return;
            }
        }
        if(container.getPaddingBottom()!=inset)container.setPadding(container.getPaddingLeft(),container.getPaddingTop(),container.getPaddingRight(),inset);
    }
    private void closePageAndDrawer(){closePage();drawer.closeDrawers();}
    /** 底栏 ≡ 与无障碍「打开侧边栏」：永远落在侧边栏主界面；停在功能子页面时先退回主界面。 */
    private void openSidebarMain(){
        if(sidebarPageOpen())closePage();
        pendingPageOpen=false;
        drawer.openDrawer(androidx.core.view.GravityCompat.START);
    }
    private void reopenPage(View body){if(!currentPage.isEmpty())openPage(currentPage,body);}
    private LinearLayout pageColumn(){LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(12),dp(6),dp(12),dp(28));return column;}
    private View pageScroll(LinearLayout column){ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.addView(column,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));return scroll;}
    /** 赞助开发者页（侧边栏二级页面）：二维码 + 说明 + 「我已诚信付款」。 */
    private View sponsorPage(){
        LinearLayout column=pageColumn();
        column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        /* 竖屏 1:2 容器（宽 168dp / 高 336dp）：白底圆角卡片，给二维码留对比度，深浅色下都扫得出。 */
        int side=dp(168);
        android.graphics.drawable.GradientDrawable card=new android.graphics.drawable.GradientDrawable();
        card.setColor(0xFFFFFFFF);card.setCornerRadius(dp(16));card.setStroke(dp(1),MusicControls.color(this,R.attr.client_outline));
        FrameLayout box=new FrameLayout(this);
        box.setLayoutParams(new LinearLayout.LayoutParams(side,side*2));
        box.setBackground(card);
        android.graphics.Bitmap bitmap=SponsorQr.get(this);
        if(bitmap!=null){
            ImageView qr=new ImageView(this);
            qr.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qr.setImageBitmap(bitmap);
            box.addView(qr);
        }else{
            TextView broken=new TextView(this);
            broken.setText("二维码加载失败");
            broken.setTextColor(MusicControls.color(this,R.attr.client_text_secondary));
            broken.setTextSize(13);
            box.addView(broken,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,android.view.Gravity.CENTER));
        }
        column.addView(box);
        TextView hint=new TextView(this);
        hint.setText("是否赞助并不会影响此应用的功能");
        hint.setTextColor(MusicControls.color(this,R.attr.client_text_secondary));
        hint.setTextSize(13);
        hint.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams hintParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin=dp(18);
        hint.setLayoutParams(hintParams);
        column.addView(hint);
        TextView paid=new TextView(this);
        paid.setText("我已诚信付款");
        paid.setGravity(android.view.Gravity.CENTER);
        paid.setTextColor(MusicControls.color(this,R.attr.client_on_accent));
        paid.setTextSize(14);
        paid.setTypeface(null,android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable fill=new android.graphics.drawable.GradientDrawable();
        fill.setColor(MusicControls.color(this,R.attr.client_accent));fill.setCornerRadius(dp(12));
        paid.setBackground(fill);
        LinearLayout.LayoutParams paidParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(44));
        paidParams.topMargin=dp(16);
        paid.setLayoutParams(paidParams);
        paid.setOnClickListener(v->{
            prefs.edit().putBoolean("sponsor_hidden",true).apply();
            android.view.MenuItem sponsor=sidebarNav==null?null:sidebarNav.getMenu().findItem(R.id.client_action_sponsor);
            if(sponsor!=null)sponsor.setVisible(false);
            closePage();
        });
        column.addView(paid);
        return pageScroll(column);
    }
    private TextView pageSection(String text){TextView t=new TextView(this);t.setText(text);t.setTextSize(12);t.setTypeface(t.getTypeface(),android.graphics.Typeface.BOLD);t.setTextColor(attrColor(R.attr.client_text_secondary));t.setPadding(dp(4),dp(14),dp(4),dp(6));return t;}
    private TextView pageHint(String text){TextView t=new TextView(this);t.setText(text);t.setTextSize(12);t.setTextColor(attrColor(R.attr.client_text_secondary));t.setPadding(dp(4),dp(6),dp(4),dp(6));t.setLineSpacing(0,1.25f);return t;}
    private LinearLayout pageRow(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setBackgroundResource(R.drawable.client_page_row);row.setPadding(dp(14),dp(10),dp(14),dp(10));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);lp.topMargin=dp(6);row.setLayoutParams(lp);return row;}
    private LinearLayout pageTexts(LinearLayout row,String title,String subtitle){LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);texts.setLayoutParams(new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        TextView t=new TextView(this);t.setText(title);t.setTextSize(14);t.setTextColor(attrColor(R.attr.client_text));texts.addView(t);
        TextView s=new TextView(this);s.setText(subtitle==null?"":subtitle);s.setTextSize(12);s.setLineSpacing(0,1.2f);s.setTextColor(attrColor(R.attr.client_text_secondary));s.setPadding(0,dp(2),0,0);s.setVisibility(subtitle==null||subtitle.isEmpty()?View.GONE:View.VISIBLE);texts.addView(s);
        row.addView(texts);return texts;}
    private TextView pageSubtitle(LinearLayout texts){return texts.getChildCount()>1?(TextView)texts.getChildAt(1):null;}
    private LinearLayout pageRowClickable(String title,String subtitle,Runnable click){LinearLayout row=pageRow();pageTexts(row,title,subtitle);row.setClickable(true);row.setFocusable(true);row.setOnClickListener(v->click.run());return row;}
    private View pageSwitch(String title,String subtitle,boolean checked,android.widget.CompoundButton.OnCheckedChangeListener listener){LinearLayout row=pageRow();pageTexts(row,title,subtitle);
        /* 无副标题的开关行（设置页顶部三个）压矮行高：上下 padding 收紧。 */
        if(subtitle==null)row.setPadding(dp(14),dp(4),dp(14),dp(4));
        SwitchCompat toggle=new SwitchCompat(this);toggle.setChecked(checked);toggle.setMinHeight(0);toggle.setMinimumHeight(0);toggle.setOnCheckedChangeListener(listener);row.addView(toggle,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT));return row;}
    private View pageButton(String text,Runnable click){TextView b=new TextView(this);b.setText(text);b.setTextSize(14);b.setGravity(android.view.Gravity.CENTER);b.setTextColor(attrColor(R.attr.client_accent));b.setBackgroundResource(R.drawable.client_page_row);b.setClickable(true);b.setFocusable(true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46));lp.topMargin=dp(10);b.setLayoutParams(lp);b.setOnClickListener(v->click.run());return b;}
    private long thumbCacheBytes(){File[] files=thumbDir().listFiles();long bytes=0;if(files!=null)for(File f:files)bytes+=f.length();return bytes;}

    /** 设置页：只保留侧边栏菜单里没有的项，避免功能重复（内部存储 / TF 卡 / 默认目录都在侧边栏）。
        内容已用 Compose 重写（见 FileManagerPages.kt）—— ComposeView 本身就是 View，
        openPage(View) 照吃，调用方一行都不用改。 */
    private View settingsPage(){return FileManagerPagesKt.settingsPageView(this);}
    /** 下载设置页（侧边栏二级页面）：代理 / tracker / 下载目录 / ed2k 服务器。同上，Compose 版。 */
    private View downloadSettingsPage(){return FileManagerPagesKt.downloadSettingsPageView(this);}
    private View favoritesPage(){return SidebarPagesKt.favoritesPageView(this);}
    private View tasksPage(){return SidebarPagesKt.tasksPageView(this);}
    private View networkPage(){return SidebarPagesKt.networkPageView(this);}
    private View appearancePage(){return SidebarPagesKt.appearancePageView(this);}
    /** 关于软件页：logo / 版本（name+code）/ 检查更新 / 反馈问题 / 开源代码许可。 */
    private View aboutPage(){return SidebarPagesKt.aboutPageView(this);}
    /** 任务页已改成 Compose（自己轮询 lastTask），不再需要手动往 TextView 里塞。 */
    private void refreshTaskText(){}
    /* ---- Compose 版二级页的桥接 ----
       Kotlin 访问不到 Java 的私有成员，需要的东西逐个开方法。
       （这套桥接是临时的：等六个页面都搬完，MainActivity 里那套 pageXxx() 手搓 DSL
         和这些私有方法都可以重新排一遍访问权限，或者干脆删掉。） */
    SharedPreferences pagePrefs(){return prefs;}
    int pageAttrColor(int attr){return attrColor(attr);}
    void pageToast(String message){toast(message);}
    boolean pageFullStorageGranted(){return fullStorageGranted();}
    void pageRequestFullAccess(){requestFullAccess();}
    void pageSetHideHidden(boolean checked){prefs.edit().putBoolean("hide_hidden",checked).apply();adapter.notifyDataSetChanged();updateActions();toast(checked?"已隐藏以 . 开头的文件":"已显示全部文件");}
    void pageSetTabletMode(boolean checked){prefs.edit().putBoolean("tablet_mode",checked).apply();applyViewMode();toast(checked?"已开启平板模式":"已关闭平板模式");}
    void pageClearThumbs(){clearThumbs();}
    /** 缩略图缓存占用：后台算，算完回填给 Compose 侧（线程 + Handler，不引协程）。 */
    long pageThumbCache()throws Exception{return thumbCacheBytes();}
    /** 网络缓存 = 打开远程（SMB/FTP）文件时下载的临时副本：PreviewFileCache 放在 cache/client-previews。
        只清理不在使用的部分：正在预览的那几份由 live 集合标记，cleanOrphans 会跳过。 */
    long pageNetworkCache(){return folderSize(new File(getCacheDir(),"client-previews"));}
    void pageClearNetworkCache(){
        io.execute(()->{
            File dir=new File(getCacheDir(),"client-previews");
            long before=folderSize(dir);
            PreviewFileCache.cleanOrphans(getCacheDir());
            long freed=before-folderSize(dir);
            runOnUiThread(()->toast(freed>0?"已清除网络缓存 "+android.text.format.Formatter.formatFileSize(this,freed):"当前没有可清除的网络缓存"));
        });
    }
    private static long folderSize(File dir){
        long bytes=0;File[] files=dir==null?null:dir.listFiles();if(files==null)return 0;
        for(File file:files)bytes+=file.isDirectory()?folderSize(file):file.length();
        return bytes;
    }
    void pageClearDefaultFolder(){prefs.edit().remove("default_uri").remove("default_name").apply();refreshDefaultFolderMenu();toast("已清除默认目录");}
    void pageApplyDownloadSettings(){applyDownloadSettings();}
    void pageEditProxy(){editProxyValue(getSharedPreferences("downloads",0));}
    void pageEditTrackers(){editTrackerList(getSharedPreferences("downloads",0));}
    void pageEditEd2kServer(){editEd2kServer(getSharedPreferences("downloads",0));}
    void pageSetDownloadDirectory(String path){getSharedPreferences("downloads",0).edit().putString("directory",path).apply();}
    /** 重新进一次当前页面：设置项改了之后副标题要跟着变（清默认目录、改代理地址都用它）。
        只覆盖已用 Compose 重写的这几个页面 —— 另外两个还是 Java 版，它们自己会 reopenPage。 */
    void reopenSettingsPage(){
        if("下载设置".equals(currentPage))openPage(currentPage,downloadSettingsPage());
        else if("设置".equals(currentPage))openPage(currentPage,settingsPage());
        else if("已收藏的目录".equals(currentPage))openPage(currentPage,favoritesPage());
        else if("传输任务".equals(currentPage))openPage(currentPage,tasksPage());
        else if("网络连接".equals(currentPage))openPage(currentPage,networkPage());
        else if("个性化".equals(currentPage))openPage(currentPage,appearancePage());
    }

    /* ---- Compose 版二级页的桥接（收藏 / 任务 / 网络 / 外观）---- */
    org.json.JSONArray pageFavorites(){return favorites();}
    void pageOpenFavorite(String uri,String name,String profileId){openFavorite(uri,name,profileId);}
    void pageRemoveFavorite(String uri,String name,String profileId){removeFavoriteDialog(uri,name,profileId);}
    void pageClearFavorites(){prefs.edit().remove("favorites").apply();updateFavoriteIcon();toast("已清空收藏");}
    String pageLastTask(){return lastTask;}
    boolean pageTransferring(){return transferring;}
    void pageCancelTransfer(){transferCancelled.set(true);RemoteSession session=transferSession;if(session!=null)session.close();lastTask="正在取消…";}
    /** 传输任务页的「已落盘文件」列表：网络位置下载到目录 / 下载到临时缓存的成品。
        Compose 每 400ms 轮询一次，而读记录要 stat 每个文件——绝不能在主线程做，
        所以只返回后台缓存，拿到的永远是上一拍的结果（顶多晚 400ms 出现）。 */
    private volatile java.util.ArrayList<TransferHistory.Item> transferCache;
    private final java.util.concurrent.atomic.AtomicBoolean transferRefreshing=new java.util.concurrent.atomic.AtomicBoolean();
    java.util.ArrayList<TransferHistory.Item> pageTransfers(){
        if(transferRefreshing.compareAndSet(false,true))io.execute(()->{try{transferCache=TransferHistory.list(this);}finally{transferRefreshing.set(false);}});
        java.util.ArrayList<TransferHistory.Item> cached=transferCache;
        return cached==null?new java.util.ArrayList<>():cached;
    }
    void pageForgetTransfer(String path){io.execute(()->TransferHistory.remove(this,path));}
    void pageClearTransfers(){io.execute(()->TransferHistory.clear(this));toast("已清空传输记录（文件仍在原处）");}
    /** 跳到落盘文件所在目录：开新标签页，上限逻辑与「＋」菜单完全一致（本地 3 / 混合 4）。
        应用私有缓存目录不需要「完整存储访问」，别为了看自己的缓存去逼用户开全盘权限。 */
    void pageRevealTransfer(String path){
        File file=new File(path==null?"":path),dir=file.getParentFile();
        if(dir==null||!dir.isDirectory()){toast("文件所在目录已不存在");return;}
        String target=dir.getAbsolutePath(),cache=getCacheDir().getAbsolutePath();
        openLocalStorage(target,true,!target.startsWith(cache+File.separator));
    }
    /** 打开落盘文件：图片进预览、音视频用内置播放器、压缩包跳目录解压、其余交给系统。 */
    void pageOpenTransfer(String path){
        File file=new File(path==null?"":path);
        if(!file.isFile()){toast("文件已不存在，可能已被清理");return;}
        Entry e=new Entry();e.uri=file.getAbsolutePath();e.name=file.getName();e.dir=false;e.mime=mimeFor(e.name);e.size=file.length();e.time=file.lastModified();e.profileId="";
        if(ImagePreviewActivity.isImage(e.name,mimeOf(e))){
            // 只预览这一张：当前标签可能是别的目录，不能像列表点击那样把同目录图片串成相册。
            ArrayList<ImagePreviewActivity.Item> one=new ArrayList<>();
            one.add(new ImagePreviewActivity.Item(e.uri,e.name,"",mimeOf(e)));
            ImagePreviewActivity.launch(this,one,0);return;
        }
        if(isPlayable(e)){
            if(isAudio(e)){ArrayList<MusicTrack> queue=new ArrayList<>();MusicTrack track=new MusicTrack(e.name,e.uri,"",e.size,e.time);queue.add(track);
                try{MusicPlaybackService.playInFolder(this,track,queue);}catch(Exception failure){error(failure);}return;}
            Intent intent=new Intent(this,VideoPlayerActivity.class);
            intent.putExtra(VideoPlayerActivity.EXTRA_PATH,e.uri);
            intent.putExtra(VideoPlayerActivity.EXTRA_PROFILE,"");
            intent.putExtra(VideoPlayerActivity.EXTRA_TITLE,e.name);
            intent.putExtra(VideoPlayerActivity.EXTRA_SIZE,e.size);
            try{startActivity(intent);}catch(Exception failure){error(failure);}return;
        }
        if(ArchiveUtil.isArchive(e.name)){pageRevealTransfer(path);toast("已跳到压缩包所在目录，点它即可解压");return;}
        ArrayList<Entry> one=new ArrayList<>();
        try{
            Entry local=new Entry();local.uri=FileProvider.getUriForFile(this,getPackageName()+".client.exports",file).toString();
            local.name=e.name;local.mime=e.mime;local.profileId="";one.add(local);
        }catch(IllegalArgumentException notExported){
            /* 目录不在 FileProvider 覆盖范围（用户可能下载到任意文件夹）：先复制进缓存再交给系统，
               跟长按「用其他应用打开」同一条路径。 */
            externalFull(singleEntry(e),true);return;
        }
        external(one,true);
    }
    private ArrayList<Entry> singleEntry(Entry entry){ArrayList<Entry> one=new ArrayList<>();one.add(entry);return one;}
    void pageReloadDirectory(){load();}
    java.util.Map<String,String> pageConnections(){return connections.names();}
    void pageConnectFromSidebar(String id){connectFromSidebar(id);}
    /** Compose 行里拿不到 View 锚点：传「⋯」按钮的窗口坐标，菜单在按钮下方弹出。 */
    void pageConnectionMenu(String id,String name,float x,float y){pageConnectionMenuAt(id,name,x,y);}
    /** 连接副标题：协议 · 主机:端口 · 起始目录。读配置可能碰磁盘，放后台再回填。 */
    void pageConnectionLabel(String id,java.util.function.Consumer<String> out){io.execute(()->{String label;try{ConnectionStore.Profile p=connections.get(id);label=protocolLabel(p.endpoint.protocol)+" · "+p.endpoint.displayHost()+":"+p.endpoint.port+" · 起始 "+p.root;}catch(Exception e){label="配置读取失败";}final String value=label;runOnUiThread(()->out.accept(value));});}
    void pageAddConnection(String title,Endpoint.Protocol protocol){openPage(title,ConnectionEditor.page(this,null,this::saveAndConnectProfile,this::closePageAndDrawer,protocol));}
    int pagePrefInt(String key,int fallback){return prefs.getInt(key,fallback);}
    void pageSetDim(int value){prefs.edit().putInt("dim",value).apply();applyBackgroundOptions();}
    void pageSetRowAlpha(int value){prefs.edit().putInt("row_alpha",value).apply();adapter.notifyDataSetChanged();}
    void pageSetFit(int position){prefs.edit().putInt("fit",position).apply();applyBackgroundOptions();}
    void pagePickBackground(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_BACKGROUND);}
    void pageRemoveBackground(){prefs.edit().remove("background").apply();((ImageView)findViewById(R.id.client_wallpaper)).setImageResource(R.drawable.client_background_plain);toast("背景已移除");}
    android.graphics.drawable.Drawable pageWallpaper(){ImageView wallpaper=findViewById(R.id.client_wallpaper);return wallpaper==null?null:wallpaper.getDrawable();}
    /** 默认“放大填充”：保持图片比例覆盖整个背景区域，宁可裁掉边缘也不露底色。 */
    ImageView.ScaleType pageWallpaperScaleType(){return prefs.getInt("fit",0)==0?ImageView.ScaleType.CENTER_CROP:ImageView.ScaleType.FIT_CENTER;}
    /** 图片缩放方式的四个选项（原来在 Spinner 的 entries 里）。 */
    String[] pageBackgroundFits(){return getResources().getStringArray(R.array.client_background_fits);}

    /** 编辑代理 host:port。默认 127.0.0.1，端口留空视为 1080（socks5 常用）。 */
    private void editProxyValue(android.content.SharedPreferences downloads){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        int p=dp(18);form.setPadding(p,dp(6),p,dp(2));
        EditText host=new EditText(this);host.setSingleLine(true);host.setHint("主机");host.setText(downloads.getString("proxy_host","127.0.0.1"));
        EditText port=new EditText(this);port.setSingleLine(true);port.setHint("端口");port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);port.setText(String.valueOf(downloads.getInt("proxy_port",1080)));
        form.addView(host);form.addView(port);
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("代理地址（SOCKS5）").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String h=host.getText().toString().trim();if(h.isEmpty())h="127.0.0.1";
            int pn;try{pn=Integer.parseInt(port.getText().toString().trim());if(pn<1||pn>65535)throw new Exception();}catch(Exception e){port.setError("端口 1-65535");return;}
            downloads.edit().putString("proxy_host",h).putInt("proxy_port",pn).apply();
            applyDownloadSettings();dialog.dismiss();reopenPage(downloadSettingsPage());
        }));
        dialog.show();
    }
    /** 编辑 tracker 列表：多行输入，每行一个 http/https announce 地址。 */
    private void editTrackerList(android.content.SharedPreferences downloads){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        int p=dp(18);form.setPadding(p,dp(6),p,dp(2));
        EditText input=new EditText(this);input.setHint("每行一个 Tracker 地址\nhttps://example.com/announce");
        input.setMinLines(6);input.setMaxLines(10);input.setGravity(android.view.Gravity.TOP);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        StringBuilder current=new StringBuilder();for(String t:LinkDownloadEngine.readTrackers(this)){current.append(t).append('\n');}
        input.setText(current.toString());
        form.addView(input);
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("Tracker 列表").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            StringBuilder out=new StringBuilder();int count=0;
            for(String line:input.getText().toString().split("\\n")){line=line.trim();if(line.isEmpty())continue;
                if(!line.startsWith("http")){input.setError("Tracker 需以 http(s):// 开头");return;}count++;out.append(line).append('\n');}
            if(count>64){input.setError("最多 64 个 tracker");return;}
            downloads.edit().putString("trackers",out.toString()).apply();
            applyDownloadSettings();dialog.dismiss();reopenPage(downloadSettingsPage());
        }));
        dialog.show();
    }
    /** 编辑 ed2k 服务器（host:port，留空=用内置轮换列表）。 */
    private void editEd2kServer(android.content.SharedPreferences downloads){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        int p=dp(18);form.setPadding(p,dp(6),p,dp(2));
        EditText server=new EditText(this);server.setSingleLine(true);server.setHint("主机:端口（留空使用内置列表）");server.setText(downloads.getString("ed2k_server",""));
        form.addView(server);
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("ed2k 服务器").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String s=server.getText().toString().trim();
            if(!s.isEmpty())try{int colon=s.lastIndexOf(':');int port=Integer.parseInt(s.substring(colon+1));if(colon<1||port<1||port>65535||s.substring(0,colon).contains("/"))throw new Exception();}catch(Exception e){server.setError("请输入主机:端口");return;}
            downloads.edit().putString("ed2k_server",s).apply();dialog.dismiss();reopenPage(downloadSettingsPage());
        }));
        dialog.show();
    }
    /** 下载设置变更后让服务即时应用（代理 applySettings、tracker 补挂；服务没起过也无妨，下次启动自然读取）。 */
    private void applyDownloadSettings(){try{LinkDownloadService.command(this,"settings",null,null,null);}catch(Exception ignored){}}

    /** 传输任务页：替换原“任务”对话框，进度实时刷新。 */
    /** 网络连接页：连接 / 编辑 / 删除 / 清除证书都在侧边栏内完成。 */
    private void connectFromSidebar(String id){io.execute(()->{try{final ConnectionStore.Profile profile=connections.get(id);runOnUiThread(()->{if(isDestroyed())return;closePageAndDrawer();connectProfile(profile);});}catch(Exception e){runOnUiThread(()->error(e));}});}
    /** 网络位置条目的「⋯」菜单：Compose 只能给按钮的窗口坐标（传 anchor View 弹 PopupMenu 会定位到整页顶部，看起来就是没反应）。
        统一用图标菜单 PopupWindow 在按钮下方弹出，右缘对齐按钮右缘并 clamp 到屏幕内。 */
    void pageConnectionMenuAt(String id,String name,float x,float y){
        View page=sidebarPage!=null?sidebarPage:sidebarNav;
        if(page==null)return;
        java.util.List<MenuItem> items=new ArrayList<>();
        items.add(new MenuItem("连接",R.drawable.client_ic_menu_network));
        items.add(new MenuItem("编辑",R.drawable.client_ic_menu_edit));
        if(connections.isFtps(id))items.add(new MenuItem("清除已信任的证书",R.drawable.client_ic_menu_password));
        items.add(new MenuItem("删除连接",R.drawable.client_ic_menu_delete));
        if(menuPopup!=null)menuPopup.dismiss();
        PopupWindow popup=buildIconMenu(null,items,n->{
            String label=items.get(n).label;
            if("连接".equals(label)){connectFromSidebar(id);return;}
            if("编辑".equals(label)){io.execute(()->{try{final ConnectionStore.Profile p=connections.get(id);runOnUiThread(()->{if(!isDestroyed())openPage("编辑连接",ConnectionEditor.page(this,p,this::saveAndConnectProfile,this::closePageAndDrawer));});}catch(Exception e){runOnUiThread(()->error(e));}});return;}
            if("清除已信任的证书".equals(label)){io.execute(()->{try{connections.save(connections.get(id).withPin(""));remoteSessions.discard(id);runOnUiThread(()->toast("已清除证书信任，下次重新核对"));}catch(Exception e){runOnUiThread(()->error(e));}});return;}
            /* 删除连接 */
            new MaterialAlertDialogBuilder(this).setTitle("删除连接配置").setMessage("将删除「"+name+"」的服务器信息与已信任的证书。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{connections.remove(id);remoteSessions.discard(id);prefs.edit().remove("conn_ok_"+id).remove("conn_info_"+id).apply();toast("已删除配置");refreshHeader();reopenPage(networkPage());}).show();
        });
        menuPopup=popup;
        /* x/y 是按钮左上角的窗口坐标：菜单右缘对齐按钮右缘、顶部贴按钮底缘，并 clamp 进屏幕。 */
        int buttonPx=dp(48);int screenW=getWindow().getDecorView().getWidth(),screenH=getWindow().getDecorView().getHeight();
        int menuX=Math.round(x)+buttonPx-popup.getWidth();
        menuX=Math.max(dp(8),Math.min(menuX,screenW-popup.getWidth()-dp(8)));
        int menuY=Math.round(y)+buttonPx+dp(4);
        menuY=Math.max(dp(8),Math.min(menuY,screenH-popup.getHeight()-dp(8)));
        popup.showAtLocation(page,Gravity.NO_GRAVITY,menuX,menuY);
    }
    /** 可用的外置存储（TF 卡 / U 盘）根目录：系统卷 API + 应用专属目录反推 + /storage、/mnt/media_rw 兜底。 */
    private ArrayList<File> externalRoots(){
        LinkedHashSet<File> roots=new LinkedHashSet<>();
        if(Build.VERSION.SDK_INT>=30)try{android.os.storage.StorageManager manager=(android.os.storage.StorageManager)getSystemService(STORAGE_SERVICE);
            for(android.os.storage.StorageVolume volume:manager.getStorageVolumes()){if(volume.isPrimary())continue;File dir=volume.getDirectory();if(dir!=null)roots.add(dir);}}catch(Throwable ignored){}
        try{File[] dirs=getExternalFilesDirs(null);for(File dir:dirs){if(dir==null)continue;String path=dir.getAbsolutePath();int cut=path.indexOf("/Android/");
            if(cut>0){File root=new File(path.substring(0,cut));String p=root.getAbsolutePath();if(!p.startsWith("/storage/emulated")&&!p.startsWith("/storage/self"))roots.add(root);}}}catch(Throwable ignored){}
        for(String base:new String[]{"/storage","/mnt/media_rw"}){File[] children=new File(base).listFiles();if(children==null)continue;
            for(File child:children){if(!child.isDirectory())continue;String name=child.getName();if(name.equals("emulated")||name.equals("self")||name.matches("\\d+"))continue;roots.add(child);}}
        ArrayList<File> out=new ArrayList<>();for(File root:roots)if(root.exists()&&root.isDirectory())out.add(root);
        return out;}
    /** 探测外置存储要遍历 /storage、/mnt/media_rw 并逐个 stat，卡慢时足以把主线程卡住：
        搬到后台线程探，探完再回主线程弹选择框；随后 openFullStorage 会立刻切标签页。 */
    private void chooseTfRoot(final boolean newTab){if(!fullStorageGranted()){requestFullAccess();return;}
        io.execute(()->{final ArrayList<File> candidates=externalRoots();runOnUiThread(()->{
            if(isFinishing()||isDestroyed())return;
            if(candidates.isEmpty()){warn("未检测到 TF 卡\n请确认已插入，并已授予完整存储访问权限");return;}
            if(candidates.size()==1){openExternalRoot(candidates.get(0),newTab);return;}
            String[] names=new String[candidates.size()];for(int i=0;i<names.length;i++)names[i]=candidates.get(i).getName()+"（"+candidates.get(i).getAbsolutePath()+"）";
            new MaterialAlertDialogBuilder(this).setTitle("选择外置存储").setItems(names,(d,n)->openExternalRoot(candidates.get(n),newTab)).show();});});
    }
    /** 外置存储根目录可能因权限或未挂载而不可读：交给 loadFull 去读，读不到会弹窗说明原因。 */
    private void openExternalRoot(File root,boolean newTab){openFullStorage(root.getAbsolutePath(),newTab);}
    /** 刷新侧边栏顶部：两块存储的真实容量，以及「快速访问」里成功连接过的 FTP 配置。 */
    private void refreshDefaultFolderMenu(){
        if(sidebarNav==null)return;
        android.view.MenuItem item=sidebarNav.getMenu().findItem(R.id.client_action_default_folder);
        if(item!=null)item.setVisible(!prefs.getString("default_uri","").isEmpty());
    }
    private void refreshHeader(){refreshDefaultFolderMenu();if(sidebarHeader==null)return;
        ((TextView)sidebarHeader.findViewById(R.id.client_internal_usage)).setText(storageSummary());
        android.os.StatFs stat=new android.os.StatFs(getFilesDir().getPath());long total=stat.getTotalBytes();
        ((ProgressBar)sidebarHeader.findViewById(R.id.client_internal_progress)).setProgress(total<=0?0:(int)(100.0*(total-stat.getAvailableBytes())/total));
        updateSdHeader(sidebarHeader);buildQuickAccess();applyMenuDock(sidebarNav);}
    /** 侧边栏功能菜单沉底：NavigationView 的菜单实际就是一个 RecyclerView，
        把它的高度改成 wrap_content、gravity 改成 BOTTOM，它就贴在存储卡片下方那块剩余空间的最底部；
        中间自然留空，视觉上「卡片在上、功能在下」。等首帧布局完成后才干，那时才有尺寸可量。 */
    private void dockSidebarMenuToBottom(final NavigationView nav){
        nav.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){
            @Override public void onGlobalLayout(){
                nav.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                applyMenuDock(nav);}});
    }
    private void applyMenuDock(NavigationView nav){
        if(nav==null)return;
        RecyclerView menu=null;
        for(int i=0;i<nav.getChildCount();i++){View child=nav.getChildAt(i);if(child instanceof RecyclerView){menu=(RecyclerView)child;break;}}
        if(menu==null)return;
        int available=nav.getHeight()-nav.getPaddingTop()-nav.getPaddingBottom();
        if(nav.getWidth()<=0||available<=0)return;
        menu.measure(View.MeasureSpec.makeMeasureSpec(nav.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.AT_MOST));
        /* 装不下（快速访问条目特别多）就退回原来的 match_parent：照常从顶往下排，仍然可以滚动。 */
        boolean fits=menu.getMeasuredHeight()<=available;
        int wantHeight=fits?ViewGroup.LayoutParams.WRAP_CONTENT:ViewGroup.LayoutParams.MATCH_PARENT;
        int wantGravity=fits?Gravity.BOTTOM:Gravity.TOP;
        android.view.ViewGroup.LayoutParams raw=menu.getLayoutParams();
        android.widget.FrameLayout.LayoutParams lp=raw instanceof android.widget.FrameLayout.LayoutParams
                ?(android.widget.FrameLayout.LayoutParams)raw
                :new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        if(lp.height==wantHeight&&lp.gravity==wantGravity)return;
        lp.height=wantHeight;lp.gravity=wantGravity;menu.setLayoutParams(lp);
    }
    private void updateSdHeader(View header){TextView usage=header.findViewById(R.id.client_sd_usage);ProgressBar bar=header.findViewById(R.id.client_sd_progress);
        View card=header.findViewById(R.id.client_host_sd);
        ArrayList<File> roots=externalRoots();
        /* 没插卡（或没权限读不到）就整块收起来：抽屉每次打开都会重新探测，插上就会自己出现。 */
        if(roots.isEmpty()){card.setVisibility(View.GONE);bar.setProgress(0);return;}
        card.setVisibility(View.VISIBLE);
        File root=roots.get(0);try{android.os.StatFs stat=new android.os.StatFs(root.getAbsolutePath());long total=stat.getTotalBytes(),used=total-stat.getAvailableBytes();
            usage.setText(root.getName()+"："+Formatter.formatFileSize(this,used)+" 已用 / "+Formatter.formatFileSize(this,total));bar.setProgress(total<=0?0:(int)(100.0*used/total));}
        catch(Exception e){usage.setText("不可读，请确认已授予完整存储访问权限");bar.setProgress(0);}}
    /** 「快速访问」：只列连接成功过的 FTP/FTPS 配置（无进度条，点击即连），长按移出列表。 */
    private void buildQuickAccess(){
        LinearLayout list=sidebarHeader.findViewById(R.id.client_quick_list);View title=sidebarHeader.findViewById(R.id.client_quick_title);
        list.removeAllViews();int shown=0;
        for(Map.Entry<String,String> e:connections.names().entrySet()){
            final String id=e.getKey();if(!prefs.getBoolean("conn_ok_"+id,false))continue;
            shown++;
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.client_sidebar_item);row.setClickable(true);row.setFocusable(true);row.setPadding(dp(12),0,dp(12),0);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(36));rp.topMargin=shown==1?0:dp(3);row.setLayoutParams(rp);
            ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.client_ic_network);icon.setColorFilter(attrColor(R.attr.client_text_secondary));
            row.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));
            LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);tp.leftMargin=dp(10);texts.setLayoutParams(tp);
            TextView name=new TextView(this);name.setText(connections.quickLabel(id));name.setTextSize(14);name.setTextColor(attrColor(R.attr.client_text));name.setTypeface(null,android.graphics.Typeface.BOLD);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
            TextView previousDetail=new TextView(this);previousDetail.setTextSize(12);
            name.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);previousDetail.setText(" ");previousDetail.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);
            rp.height=Math.round((dp(24)+name.getMeasuredHeight()+previousDetail.getMeasuredHeight())*0.6f);
            texts.addView(name);row.addView(texts);
            row.setOnClickListener(v->{closePage();drawer.closeDrawers();openQuickConnection(id);});
            row.setOnLongClickListener(v->{prefs.edit().putBoolean("conn_ok_"+id,false).apply();toast("已移出快速访问（配置仍保留在网络连接里）");buildQuickAccess();return true;});
            list.addView(row);
        }
        title.setVisibility(shown==0?View.GONE:View.VISIBLE);
    }
    private void openQuickConnection(String id){
        io.execute(()->{try{ConnectionStore.Profile p=connections.get(id);runOnUiThread(()->{if(isDestroyed())return;connectProfile(p);});}
            catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;error(e);});}});
    }
    /** 个性化页：背景、遮罩、卡片不透明度即时生效；深浅色切换重建界面。 */
    private void importBackground(Uri uri){io.execute(()->{try{Drawable drawable=decodeBackground(uri);runOnUiThread(()->{if(isDestroyed())return;/* 新图片默认放大填充，避免沿用上次“完整显示”留下的大块底色。 */prefs.edit().putString("background",uri.toString()).putInt("fit",0).apply();
        ((ImageView)findViewById(R.id.client_wallpaper)).setImageDrawable(drawable);applyBackgroundOptions();adapter.notifyDataSetChanged();toast("图片背景已设置");if("个性化".equals(currentPage))reopenPage(appearancePage());});}catch(Exception|OutOfMemoryError e){runOnUiThread(()->toast("图片读取失败，请选择其他图片"));}});}
    private Drawable decodeBackground(Uri uri)throws IOException{return ImageDecoder.decodeDrawable(ImageDecoder.createSource(getContentResolver(),uri),(decoder,info,source)->{int w=info.getSize().getWidth(),h=info.getSize().getHeight();if(w<=0||h<=0||(long)w*h>160000000L)throw new IllegalArgumentException("图片过大");float scale=Math.min(1f,1600f/Math.max(w,h));decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});}
    private void cleanExpiredExports(){
        io.execute(()->{PreviewFileCache.cleanOrphans(getCacheDir());File root=new File(getCacheDir(),"client-exports");File[] dirs=root.listFiles();if(dirs==null)return;long cutoff=System.currentTimeMillis()-7L*24*60*60*1000;
            for(File dir:dirs){if(!dir.isDirectory()||dir.lastModified()>=cutoff||!dir.getName().matches("[a-fA-F0-9-]{36}"))continue;
                File[] files=dir.listFiles();if(files!=null)for(File file:files)if(file.isFile())file.delete();dir.delete();}
        });
    }
    private void hideSearch(){search.setText("");search.clearFocus();search.setVisibility(View.GONE);
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(search.getWindowToken(),0);}
    /** 本地及 SMB 媒体缩略图；缓存按连接、路径、修改时间和大小隔离。 */
    private File thumbDir(){return new File(getCacheDir(),"thumbs");}
    /** 缩略图解码目标边长（像素）。
        按图标的真实像素需求来定：宫格图标 76dp、列表图标 36dp，取两者最大值再乘屏幕密度，
        夹在 160–320 之间。以前恒为 160，在 3x 屏的宫格里要放大到 228px 才够铺满，
        等于把一张小图拉大——这才是大图缩略图"发糊"的主因之一。
        列表与宫格共用同一份缓存，避免切换视图时整屏重新解码。 */
    private int thumbTarget(){
        float density=getResources().getDisplayMetrics().density;
        return Math.max(160,Math.min(320,(int)Math.ceil(76f*density)));
    }
    /** 大文件直接不解码缩略图：图片 >5MB、视频 >100MB 一律只显示类型图标，省下大量 IO 与解码开销。
        PSD 例外：只读文件头的资源区，几百 MB 的文件也只需几十 KB 的顺序读，不受体积限制。
        音频文件读取内嵌封面或同目录同名图片，不解码整个音频流，不受文件体积限制。 */
    private static final long THUMB_MAX_IMAGE=5L*1024*1024, THUMB_MAX_VIDEO=100L*1024*1024;
    private boolean thumbAllowed(Entry e){
        if(PsdThumbnail.matches(e.name))return !e.dir&&e.size>0;
        if(!e.profileId.isEmpty()){
            if(e.mime.startsWith("image/"))return e.size>0&&e.size<=32L*1024*1024;
            return (e.mime.startsWith("video/")||e.mime.startsWith("audio/"))&&e.size>0;
        }
        if(e.mime.startsWith("image/"))return e.size>0&&e.size<=THUMB_MAX_IMAGE;
        if(e.mime.startsWith("video/"))return e.size>0&&e.size<=THUMB_MAX_VIDEO;
        return e.mime.startsWith("audio/")&&e.size>0;
    }
    /** 目标边长参与缓存键：换设备/换密度后旧的低清缓存自动失效，不会串味。 */
    private String thumbKey(Entry e){return thumbKey(e,thumbTarget());}
    private String thumbKey(Entry e,int target){return target+"|"+(e.profileId.isEmpty()?"local":connections.thumbnailScope(e.profileId))+"|"+e.uri+"|"+e.time+"|"+e.size;}
    private File thumbFile(Entry e){return thumbFile(e,thumbTarget());}
    private File thumbFile(Entry e,int target){String key=thumbKey(e,target),name;try{byte[] d=java.security.MessageDigest.getInstance("MD5").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(Locale.ROOT,"%02x",x));name=b.toString();}catch(Exception ex){name=Integer.toHexString(key.hashCode());}return new File(thumbDir(),name+"_"+target+".jpg");}
    private void requestThumb(Entry e,ImageView icon){
        String key=thumbKey(e);icon.setTag(R.id.client_file_icon,key);android.graphics.Bitmap hit=thumbCache.get(key);
        if(hit!=null){icon.setImageBitmap(hit);icon.setColorFilter(null);return;}
        icon.setTag(R.id.client_file_icon,key);
        Long failed=unavailableThumbnails.get(key);if(failed!=null&&System.nanoTime()-failed<60_000_000_000L)return;
        if(thumbnailsClosed)return;
        if(pendingThumbnails.contains(key)){thumbnailObservers.computeIfAbsent(key,k->new ArrayList<>()).add(new java.lang.ref.WeakReference<>(icon));return;}
        if(pendingThumbnails.size()>=64||!pendingThumbnails.add(key))return;
        thumbnailObservers.computeIfAbsent(key,k->new ArrayList<>()).add(new java.lang.ref.WeakReference<>(icon));
        thumbIo.execute(()->{try{android.graphics.Bitmap bmp=decodeThumb(e);if(bmp!=null){thumbCache.put(key,bmp);unavailableThumbnails.remove(key);}else{if(unavailableThumbnails.size()>512)unavailableThumbnails.clear();unavailableThumbnails.put(key,System.nanoTime());}
            runOnUiThread(()->{ArrayList<java.lang.ref.WeakReference<ImageView>> observers=thumbnailObservers.remove(key);if(isDestroyed()||bmp==null||observers==null)return;for(java.lang.ref.WeakReference<ImageView> observer:observers){ImageView target=observer.get();if(target!=null&&key.equals(target.getTag(R.id.client_file_icon))){target.setImageBitmap(bmp);target.setColorFilter(null);}}});}finally{pendingThumbnails.remove(key);}});
    }
    private android.graphics.Bitmap decodeThumb(Entry e){
        final int target=thumbTarget();
        try{if(thumbnailsClosed||Thread.currentThread().isInterrupted())return null;File disk=thumbFile(e,target);
            if(disk.exists()){android.graphics.Bitmap saved=android.graphics.BitmapFactory.decodeFile(disk.getPath());if(saved!=null)return saved;}
            android.graphics.Bitmap raw=null;
            if(!e.profileId.isEmpty())raw=decodeRemoteThumb(e,target);
            else if(PsdThumbnail.matches(e.name))raw=decodeLocalPsdThumb(e,target);
            else if(e.mime.startsWith("image/"))raw=decodeSampled(new File(e.uri),target);
            else if(e.mime.startsWith("video/"))raw=decodeVideoFrame(e.uri,target);
            else if(e.mime.startsWith("audio/"))raw=decodeAudioCover(e,target);
            if(raw==null)return null;
            android.graphics.Bitmap out=shrink(raw,target);
            if(out!=raw)raw.recycle();
            SafeFileOperations.writeReplacing(disk,stream->out.compress(android.graphics.Bitmap.CompressFormat.JPEG,88,stream));
            return out;
        }catch(Throwable t){return null;}
    }
    /** 本地视频取帧：直接要解码器按目标尺寸出帧，别把 4K 原始帧（33MB）解出来再自己缩。 */
    private android.graphics.Bitmap decodeVideoFrame(String path,int target){
        android.media.MediaMetadataRetriever r=new android.media.MediaMetadataRetriever();
        try{r.setDataSource(path);
            try{android.graphics.Bitmap scaled=r.getScaledFrameAtTime(-1,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,target,target);
                if(scaled!=null)return scaled;
            }catch(Throwable ignored){}
            return r.getFrameAtTime();
        }catch(Throwable t){return null;}
        finally{try{r.release();}catch(Throwable ignored){}}
    }
    /** 音频文件封面：优先读内嵌封面，没有则找同目录同名图片。 */
    private android.graphics.Bitmap decodeAudioCover(Entry e,int target){
        if(!e.profileId.isEmpty())return null;
        try{
            MusicTrack track=new MusicTrack(e.name,e.uri,"",e.size,e.time);
            return MusicLocalArtwork.read(this,track,target);
        }catch(Throwable t){return null;}
    }
    /** 高质量缩小：先按 2 的幂逐次减半逼近目标，最后一步一次缩到位。
        单步双线性缩放只采源图 2×2 邻域，大比例缩小（如 375→160）会漏掉绝大多数像素，
        出来的缩略图发虚、有摩尔纹；逐次减半等效于逐级做盒式平均，细节保留明显更好。
        代价很小：每减半一次，下一级要处理的像素只有 1/4，全部中间级加起来约为首帧的 1/3，
        而且首帧已经被 inSampleSize 压到目标 2–4 倍，绝对量本来就小。 */
    private android.graphics.Bitmap shrink(android.graphics.Bitmap src,int target){
        android.graphics.Bitmap current=src;
        try{
            while(Math.max(current.getWidth(),current.getHeight())>target*2){
                int nw=Math.max(1,current.getWidth()/2),nh=Math.max(1,current.getHeight()/2);
                android.graphics.Bitmap next=android.graphics.Bitmap.createBitmap(nw,nh,android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas=new android.graphics.Canvas(next);
                android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG|android.graphics.Paint.ANTI_ALIAS_FLAG);
                canvas.drawBitmap(current,null,new android.graphics.Rect(0,0,nw,nh),paint);
                if(current!=src)current.recycle();
                current=next;
            }
            int w=current.getWidth(),h=current.getHeight();
            if(Math.max(w,h)<=target){if(current!=src)src.recycle();return current;}
            float scale=(float)target/Math.max(w,h);
            int tw=Math.max(1,Math.round(w*scale)),th=Math.max(1,Math.round(h*scale));
            android.graphics.Bitmap out=android.graphics.Bitmap.createScaledBitmap(current,tw,th,true);
            if(current!=src&&out!=current)current.recycle();
            return out;
        }catch(Throwable t){return current;}
    }
    private android.graphics.Bitmap decodeLocalPsdThumb(Entry e,int target){
        try(InputStream stream=new java.io.BufferedInputStream(new java.io.FileInputStream(e.uri),32*1024)){
            return PsdThumbnail.read(stream,target);
        }catch(Throwable t){return null;}
    }
    private android.graphics.Bitmap decodeRemoteThumb(Entry e,int target)throws Exception{
        ConnectionStore.Profile profile=connections.get(e.profileId);if(profile==null)return null;
        RemoteSession session=RemoteSessions.newSession(profile.endpoint);thumbnailSessions.add(session);
        try{
            if(thumbnailsClosed)return null;session.connect();
            if(PsdThumbnail.matches(e.name)){
                /* 缩略图就在文件头：开一条只读前 8MB 的流，扫到就立刻关掉，不等整个文件传完。
                   提前 close 依赖的是 FtpSession/SmbSession 自己的中断语义（图片取尺寸那条路已经在用）。 */
                try(InputStream stream=new com.cj.music.net.BoundedRemoteInputStream(session.openStream(e.uri,0),8L*1024*1024)){
                    return PsdThumbnail.read(stream,target);
                }
            }
            if(e.mime.startsWith("image/")){
                android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                try(InputStream stream=new com.cj.music.net.BoundedRemoteInputStream(session.openStream(e.uri,0),8L*1024*1024)){android.graphics.BitmapFactory.decodeStream(stream,null,bounds);}
                android.graphics.BitmapFactory.Options options=sampleOptions(bounds,target);
                try(InputStream stream=new com.cj.music.net.BoundedRemoteInputStream(session.openStream(e.uri,0),32L*1024*1024)){return android.graphics.BitmapFactory.decodeStream(stream,null,options);}
            }
            long length=session.streamSize(e.uri);if(length<=0)return null;
            if(!profile.endpoint.smb()&&length>1)try(InputStream test=session.openStream(e.uri,1)){test.read();}
            final RemoteRandomAccessReader reader=new RemoteRandomAccessReader(session,e.uri,length,24L*1024*1024);
            android.media.MediaDataSource source=new android.media.MediaDataSource(){
                @Override public int readAt(long position,byte[] buffer,int offset,int size)throws IOException{return reader.readAt(position,buffer,offset,size);}
                @Override public long getSize(){return reader.size();}
                @Override public void close()throws IOException{reader.close();}
            };
            android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
            try{
                retriever.setDataSource(source);
                if(e.mime.startsWith("audio/")){
                    byte[] bytes=retriever.getEmbeddedPicture();if(bytes==null)return null;
                    android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                    android.graphics.BitmapFactory.Options options=sampleOptions(bounds,target);
                    return android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                }
                return retriever.getScaledFrameAtTime(-1,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,target,target);
            }
            finally{try{retriever.release();}finally{source.close();}}
        }finally{session.close();thumbnailSessions.remove(session);}
    }
    /** inSampleSize 只能取 2 的幂，所以这里刻意留一倍余量，让解码结果落在 [2×target, 4×target)。
        以前是"刚好压到 >= target"——6000px 宽的图会一步降到 187px，丢掉的像素后面再怎么插值也补不回来。
        剩下的精确缩放交给 shrink() 逐级完成，解码峰值仍被控制在 4×target 见方以内（约 6MB，低于原图解码）。
        inPreferQualityOverSpeed 让解码器走高质量的 DCT 降采样分支，只是略慢一点，对一次性缩略图很划算。 */
    private android.graphics.BitmapFactory.Options sampleOptions(android.graphics.BitmapFactory.Options bounds,int target){
        int max=Math.max(bounds.outWidth,bounds.outHeight);
        int sample=1;while(max/(sample*2)>=target*2)sample*=2;
        android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();
        options.inSampleSize=sample;options.inPreferQualityOverSpeed=true;return options;
    }
    private android.graphics.Bitmap decodeSampled(File f,int target){
        android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(f.getPath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
        android.graphics.BitmapFactory.Options opts=sampleOptions(bounds,target);
        return android.graphics.BitmapFactory.decodeFile(f.getPath(),opts);
    }
    private void clearThumbs(){io.execute(()->{File dir=thumbDir();File[] files=dir.listFiles();long bytes=0;if(files!=null)for(File f:files)bytes+=f.length();if(files!=null)for(File f:files)f.delete();dir.delete();final long freed=bytes;runOnUiThread(()->{if(isDestroyed())return;thumbCache.evictAll();adapter.notifyDataSetChanged();toast(freed>0?"已清除缩略图缓存（释放 "+Formatter.formatFileSize(this,freed)+"）":"暂无缩略图缓存");if("设置".equals(currentPage))reopenPage(settingsPage());});});}
    private void loadBackground(){applyBackgroundOptions();String s=prefs.getString("background","");if(!s.isEmpty())io.execute(()->{try{Drawable d=decodeBackground(Uri.parse(s));runOnUiThread(()->{if(!isDestroyed())((ImageView)findViewById(R.id.client_wallpaper)).setImageDrawable(d);});}catch(Exception|OutOfMemoryError e){runOnUiThread(()->toast("背景图片已不可用，请重新选择"));}});}
    private void applyBackgroundOptions(){findViewById(R.id.client_wallpaper_scrim).setAlpha(prefs.getInt("dim",50)/100f);((ImageView)findViewById(R.id.client_wallpaper)).setScaleType(pageWallpaperScaleType());}
    private void toast(String s){if(!isDestroyed())Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    /** 阻断性/警告提示改用弹窗，避免系统气泡一闪而过被忽略。 */
    private void warn(String s){if(!isFinishing()&&!isDestroyed())new MaterialAlertDialogBuilder(this).setMessage(s).setPositiveButton("知道了",null).show();}
    private void message(String title,String s){if(!isFinishing()&&!isDestroyed())new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(s).setPositiveButton("知道了",null).show();}
    private void error(Exception e){message("操作失败",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
    @Override protected void onStop(){persist();super.onStop();}
    @Override protected void onDestroy(){thumbnailsClosed=true;for(RemoteSession session:thumbnailSessions)session.close();thumbnailSessions.clear();generation++;transferCancelled.set(true);if(transferSession!=null)transferSession.close();remoteSessions.close();browseIo.shutdownNow();transferIo.shutdownNow();thumbIo.shutdownNow();/* shutdownNow 会中断正在跑的解压线程，ArchiveUtil 的 checkCancelled 认 isInterrupted，任务按「已取消」收尾，不会在 Activity 销毁后继续写盘。 */io.shutdownNow();if(archiveDialog!=null)archiveDialog.dismiss();cancelLoadDialog();if(folderPopup!=null)folderPopup.dismiss();popupDismiss();/* 文件操作进行中弹窗：旋转 / 回收时统一 dismiss，否则 showing 中的窗口即 WindowLeaked */for(android.app.ProgressDialog dialog:operationDialogs){try{dialog.dismiss();}catch(Throwable ignored){}}operationDialogs.clear();super.onDestroy();}
    private String protocolLabel(Endpoint.Protocol p){
        return p==Endpoint.Protocol.FTP?"FTP"
                :p==Endpoint.Protocol.SMB?"SMB"
                :p==Endpoint.Protocol.WEBDAV?"WebDAV"
                :p==Endpoint.Protocol.OPENLIST?"OpenList":"FTPS";
    }
    /** 打开网络位置：先把标签页切过去（空页 + 转圈），连接、列目录全在后台排队。
        以前要等连上并读完根目录才建标签页，慢网络下用户根本切不进这个位置。 */
    private void connectProfile(ConnectionStore.Profile profile){
        if(!canNewRemoteTab())return;
        TabState t=new TabState();t.profileId=profile.id;t.label=profile.name;t.stack.add(new Place(profile.root,profile.name));
        tabs.add(t);active=tabs.size()-1;selected.clear();search.setText("");entries.clear();
        renderTabs();load();
    }
    /** 新增/编辑连接后：先落盘，保存成功再开标签页连接。否则 connections.get(profileId) 会报配置不存在。 */
    private void saveAndConnectProfile(ConnectionStore.Profile profile){
        io.execute(()->{
            try{connections.save(profile);
                // 保存成功 → 连接草稿已无用途，清掉避免下次表单误回填过期内容。
                android.content.SharedPreferences drafts=getSharedPreferences("connection_drafts",MODE_PRIVATE);
                android.content.SharedPreferences.Editor clear=drafts.edit();
                for(String key:drafts.getAll().keySet())if(key.startsWith("conn_draft_"))clear.remove(key);
                clear.apply();
                runOnUiThread(()->connectProfile(profile));}
            catch(Exception e){runOnUiThread(()->error(e));}
        });
    }
    /** 把连接异常归类为可读的原因（密码错误 / 超时 / 拒绝访问 / 通用）。 */
    private void connectionError(ConnectionStore.Profile profile,Exception error,Runnable retry){
        if(error instanceof CertificateApprovalException){CertificateApprovalException certificate=(CertificateApprovalException)error;
            String fingerprint=certificate.fingerprint.replaceAll("(..)(?!$)","$1:");
            new MaterialAlertDialogBuilder(this).setTitle("核对服务器证书").setMessage(profile.endpoint.host+":"+profile.endpoint.port+"\n"+certificate.subject+"\n有效期至 "+certificate.expires+"\n\nSHA-256\n"+fingerprint+"\n\n请与服务器显示的指纹核对。确认后仅信任此地址、端口与证书；证书变化将拒绝连接。").setNegativeButton("取消",null).setPositiveButton("指纹一致，信任并连接",(d,w)->retry.run()).show();
        }else{
            String detail=error.getMessage()==null?"":error.getMessage();
            String hint;
            if(detail.contains("密码")||detail.contains("账户")||detail.contains("登录失败")||detail.contains("认证")||detail.contains("STATUS_LOGON")||detail.contains("AUTH"))hint="账号或密码错误，请核对后重试。";
            else if(error instanceof java.net.SocketTimeoutException||error instanceof java.net.ConnectException||detail.contains("超时")||detail.contains("timed out")||detail.contains("timeout"))hint="连接超时，请检查服务器地址、端口与网络是否可达。";
            else if(error instanceof java.net.UnknownHostException||detail.contains("无法解析")||detail.contains("UnknownHost"))hint="无法解析服务器地址，请检查主机名或 IP。";
            else if(error instanceof java.net.SocketException||detail.contains("拒绝")||detail.contains("refused")||detail.contains("reset"))hint="服务器拒绝连接，请确认端口正确、服务已开启。";
            else if(detail.contains("用户名或密码")||detail.contains("HTTP 401"))hint="账号或密码错误，请核对后重试。";
            else if(detail.contains("HTTP 404")||detail.contains("目标不存在"))hint="路径不存在，请检查起始目录与共享名。";
            else if(detail.contains("HTTP"))hint="服务器返回了错误状态，请检查路径与服务配置。";
            else hint="连接失败，请检查网络与服务器配置。";
            new MaterialAlertDialogBuilder(this).setTitle("连接失败").setMessage(hint+(detail.isEmpty()?"":"\n\n详情："+detail)).setPositiveButton("知道了",null).show();
        }
    }
    private Entry remoteEntry(String profileId,RemoteEntry remote){Entry e=new Entry();e.uri=remote.path;e.profileId=profileId;e.name=remote.name;e.dir=remote.directory;e.size=remote.size;e.time=remote.modified;e.mime=remote.directory?DocumentsContract.Document.MIME_TYPE_DIR:mimeFor(remote.name);return e;}
    private String mimeFor(String name){int dot=name.lastIndexOf('.');String type=dot<0?null:android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));return type==null?"application/octet-stream":type;}
    /* ===== 文件类型图标：后缀优先（MimeTypeMap 对 7z/apkx/gz/exe 常返回 null 或错 mime），mime 兜底 ===== */
    private int iconFor(Entry e){if(java.util.Arrays.asList("torrent","magnet","ed2k").contains(extensionOf(e.name)))return R.drawable.client_ic_magnet_file;
        String name=e.name==null?"":e.name;
        int dot=name.lastIndexOf('.');
        switch(dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT)){
            case "zip":case "rar":case "7z":case "gz":case "tar":case "tgz":case "bz2":case "xz":case "zst":return R.drawable.client_ic_archive;
            case "txt":case "json":case "md":case "log":case "xml":case "ini":case "yml":case "yaml":case "conf":case "prop":case "properties":return R.drawable.client_ic_text;
            case "psd":case "psb":return R.drawable.client_ic_psd;
            case "pdf":return R.drawable.client_ic_pdf;
            case "doc":case "docx":case "odt":case "rtf":case "wps":return R.drawable.client_ic_doc;
            case "xls":case "xlsx":case "ods":case "csv":case "et":return R.drawable.client_ic_xls;
            case "ppt":case "pptx":case "odp":case "dps":return R.drawable.client_ic_ppt;
            case "java":case "kt":case "kts":case "py":case "js":case "jsx":case "ts":case "tsx":case "c":case "cc":case "cpp":case "cxx":case "h":case "hpp":case "go":case "rs":case "rb":case "php":case "swift":case "scala":case "gradle":case "sql":case "css":case "html":case "htm":case "vue":case "dart":case "lua":case "pl":case "r":case "cs":return R.drawable.client_ic_code;
            case "apk":case "apkx":case "apks":case "xapk":case "aab":return R.drawable.client_ic_apk;
            case "exe":case "msi":case "bat":case "cmd":case "sh":return R.drawable.client_ic_exe;
            default:break;
        }
        String mime=e.mime==null?"":e.mime.toLowerCase(Locale.ROOT);
        if(mime.contains("zip")||mime.contains("rar")||mime.contains("7z")||mime.contains("gzip")||mime.contains("x-tar")||mime.contains("compress"))return R.drawable.client_ic_archive;
        if(mime.contains("android.package-archive"))return R.drawable.client_ic_apk;
        if(mime.contains("msdownload")||mime.contains("msdos")||mime.contains("x-msi")||mime.contains("x-executable")||mime.contains("x-sharedlib"))return R.drawable.client_ic_exe;
        if(mime.contains("pdf"))return R.drawable.client_ic_pdf;
        if(mime.contains("msword")||mime.contains("wordprocessingml")||mime.contains("opendocument.text")||mime.contains("rtf"))return R.drawable.client_ic_doc;
        if(mime.contains("msexcel")||mime.contains("spreadsheetml")||mime.contains("opendocument.spreadsheet"))return R.drawable.client_ic_xls;
        if(mime.contains("mspowerpoint")||mime.contains("presentationml")||mime.contains("opendocument.presentation"))return R.drawable.client_ic_ppt;
        if(mime.contains("javascript")||mime.contains("x-c")||mime.contains("x-c++")||mime.contains("x-java")||mime.contains("x-python")||mime.contains("x-sql")||mime.contains("x-shellscript"))return R.drawable.client_ic_code;
        if(mime.startsWith("text/")||mime.contains("json")||mime.contains("xml"))return R.drawable.client_ic_text;
        if(mime.startsWith("image/"))return R.drawable.client_ic_image;
        if(mime.startsWith("video/"))return R.drawable.client_ic_video;
        if(mime.startsWith("audio/"))return R.drawable.client_ic_audio;
        return R.drawable.client_ic_file;
    }
    private void loadRemote(TabState target,int ticket,String anchor,int anchorOffset,int restoreIndex){String directory=target.current(),profileId=target.profileId,key=dirKey(target);
        browseIo.execute(()->{
            // 浏览队列是单线程：排在后面的任务可能已经因为用户继续滑动切换而作废，
            // 启动时就该直接退出，否则一个十几秒的登录会把后续切换全堵住。
            if(generation!=ticket)return;
            try{ConnectionStore.Profile p=connections.get(profileId);List<RemoteEntry> found=remoteSessions.list(p,directory);
                runOnUiThread(()->applyLoad(ticket,key,()->{
                    if(!prefs.getBoolean("conn_ok_"+profileId,false)){prefs.edit().putBoolean("conn_ok_"+profileId,true).putString("conn_info_"+profileId,protocolLabel(p.endpoint.protocol)+" · "+p.endpoint.host+":"+p.endpoint.port).apply();buildQuickAccess();}
                    entries.clear();for(RemoteEntry r:found)entries.add(remoteEntry(profileId,r));path.setText(fullPath(target));sortEntries();scrollAfterLoad(anchor,anchorOffset,restoreIndex);}));}
            catch(Exception e){remoteSessions.discard(profileId);runOnUiThread(()->{if(isDestroyed()||generation!=ticket)return;endLoad(ticket);entries.clear();adapter.notifyDataSetChanged();path.setText("读取失败 · 页面保持空白");openFailure(profileId,e,()->load());});}});
    }
    /** 打开网络位置失败：证书要单独核对，其余按可读原因归类后弹窗，页面留在空白。
        以前这里只有 error(e)——一句原始异常信息，看不出是密码错、超时还是地址解析不了。 */
    private void openFailure(String profileId,Exception e,Runnable retry){
        ConnectionStore.Profile profile=null;try{profile=connections.get(profileId);}catch(Exception ignored){}
        if(e instanceof CertificateApprovalException&&profile!=null){
            ConnectionStore.Profile approved=profile.withPin(((CertificateApprovalException)e).fingerprint);
            try{connections.save(approved);}catch(Exception ignored){}
            connectionError(approved,e,retry);return;
        }
        if(profile!=null){connectionError(profile,e,retry);return;}
        message("无法打开网络位置",reason(e));
    }
    private interface RemoteJob {void run(RemoteSession session)throws Exception;}
    private interface MutationJob {void run()throws Exception;}
    /** 批量远程变更：进度回调可能在任意 worker 线程，实现方只负责把数字送到 UI。 */
    private interface BatchMutationJob {String run(com.cj.music.net.RemoteMutation.Progress progress)throws Exception;}
    private void remoteMutation(String title,String id,MutationJob work){if(busy){toast("请等待当前操作完成");return;}busy=true;browseIo.execute(()->{String failure=null;try{work.run();}catch(Exception e){failure=reason(e);remoteSessions.discard(id);}final String result=failure;runOnUiThread(()->{busy=false;if(isDestroyed())return;if(result==null)toast(title+"完成");else message(title+"失败",result);load();});});}
    /** 带进度的远程批处理（批量删除走这条）： determinate 弹窗显示「已完成 / 总数」，
     *  而不是一个猜不到头绪的 indeterminate 转圈。弹窗登记进 operationDialogs，
     *  旋转 / 回收时由 onDestroy 统一 dismiss。 */
    private void remoteBatchMutation(String title,String id,int total,BatchMutationJob work){
        if(busy){toast("请等待当前操作完成");return;}
        busy=true;lastTask=title+"…";
        final android.app.ProgressDialog operation=fileOperationDialog(title);
        operation.setIndeterminate(false);operation.setMax(Math.max(1,total));operation.setProgress(0);
        operation.setMessage("正在"+title+" 0 / "+total);
        browseIo.execute(()->{
            String failure=null,notice=null;
            try{notice=work.run((completed,done,current)->runOnUiThread(()->{
                if(isDestroyed())return;
                operation.setProgress(completed);
                operation.setMessage("正在"+title+" "+completed+" / "+done+(current==null||current.isEmpty()?"":"\n"+current));
            }));}
            catch(Exception e){failure=reason(e);remoteSessions.discard(id);}
            final String result=failure;final String hint=notice;
            runOnUiThread(()->{
                busy=false;dismissOperationDialog(operation);
                if(isDestroyed())return;
                if(hint!=null)toast(hint);
                if(result==null)toast(title+"完成");else message(title+"失败",result);
                load();
            });
        });
    }
    private com.cj.music.net.RemoteMutation.Access mutationAccess(String id){return new com.cj.music.net.RemoteMutation.Access(){public RemoteSession session()throws IOException{try{return remoteSessions.browse(connections.get(id));}catch(IOException e){throw e;}catch(Exception e){throw new IOException("读取连接配置失败",e);}}public void discard(){remoteSessions.discard(id);}};}
    private void checkedMutation(String id,com.cj.music.net.RemoteMutation.Kind kind,String parent,String from,String name,boolean dir)throws IOException{String notice=com.cj.music.net.RemoteMutation.run(mutationAccess(id),kind,parent,from,name,dir);if(notice!=null)runOnUiThread(()->toast(notice));}
    private void remoteMkdir(){String id=tab().profileId,parent=tab().current();nameDialog("新建远程目录","",name->remoteMutation("新建目录",id,()->checkedMutation(id,com.cj.music.net.RemoteMutation.Kind.MKDIR,parent,"",name,true)));}
    private void remoteRename(Entry entry){String parent=tab().current();nameDialog("重命名",entry.name,name->remoteMutation("重命名",entry.profileId,()->checkedMutation(entry.profileId,com.cj.music.net.RemoteMutation.Kind.RENAME,parent,entry.uri,name,entry.dir)));}
    private void remoteDelete(ArrayList<Entry> chosen){
        String id=tab().profileId,parent=tab().current();
        new MaterialAlertDialogBuilder(this).setTitle("删除远程项目？").setMessage("将删除服务器上的 "+chosen.size()+" 个项目。仅支持文件和空文件夹；链接仅删除链接本身，不删除目标。").setPositiveButton("删除",(d,w)->{
            /* 批量删除：同目录只列一次、只探一次，SMB 上 4 路并发发删除（见 RemoteMutation.deleteBatch）。
               以前是循环调单项接口——每删一个文件就把整个父目录重列一遍，大目录下这是主要耗时，
               而且整个批次没有任何进度提示。 */
            ArrayList<com.cj.music.net.RemoteMutation.Item> items=new ArrayList<>();
            for(Entry entry:chosen)items.add(new com.cj.music.net.RemoteMutation.Item(entry.uri,entry.name,entry.dir));
            remoteBatchMutation("删除",id,items.size(),progress->com.cj.music.net.RemoteMutation.deleteBatch(mutationAccess(id),parent,items,progress));
        }).setNegativeButton("取消",null).show();
    }
    private void showTasks(){openPage("传输任务",tasksPage());}
    private interface TransferWork {void run(RemoteSession session)throws Exception;}
    private void transfer(String title,String profileId,TransferWork work,Runnable success){
        if(transferring){toast("已有传输正在进行，请等待或取消");return;}transferring=true;transferCancelled.set(false);lastTask=title+"：正在连接…";progressTime=0;showTasks();
        transferIo.execute(()->{String failure=null;try{checkCancelled();ConnectionStore.Profile profile=connections.get(profileId);try(RemoteSession session=RemoteSessions.newSession(profile.endpoint)){transferSession=session;checkCancelled();session.connect();work.run(session);checkCancelled();}}
            catch(Exception e){failure=transferCancelled.get()&&!(e instanceof LocalTransferFiles.IncompleteCommitException)?"已取消":reason(e);}finally{transferSession=null;}
            final String result=failure;runOnUiThread(()->{transferring=false;if(isDestroyed())return;lastTask=result==null?title+"完成":title+"："+result;if("传输任务".equals(currentPage))reopenPage(tasksPage());else refreshTaskText();if(result!=null)message("传输未完成",lastTask);else if(success!=null)success.run();if(result==null)load();});});
    }
    private void checkCancelled()throws IOException {if(transferCancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消");}
    private void progress(String name,long bytes)throws IOException {checkCancelled();long now=System.nanoTime();if(now-progressTime<250_000_000L)return;progressTime=now;runOnUiThread(()->{if(isDestroyed())return;lastTask=name+"\n已传输 "+Formatter.formatFileSize(this,bytes);refreshTaskText();});}
    private void downloadDefault(){ArrayList<Entry> files=selectedEntries();if(files.isEmpty()||files.get(0).profileId.isEmpty()){toast("请选择远程文件");return;}String dest=prefs.getString("default_uri","");if(dest.isEmpty()){toast("请先进入手机目录并将其设为默认接收目录");return;}File folder=new File(dest);if(!folder.isDirectory()){toast("默认目录不可用，请重新设置");return;}downloadFiles(files,folder);}
    /** 系统公共下载目录：固定取主存储（机内 ROM）下的 Download，与「默认目录」无关，也不会落到 TF 卡。 */
    private File systemDownloadFolder(){
        File primary=Environment.getExternalStorageDirectory();
        if(primary!=null){
            File standard=new File(primary,"Download");
            if(standard.isDirectory())return standard;
            File alternative=new File(primary,"Downloads");
            if(alternative.isDirectory())return alternative;
            if(standard.mkdirs())return standard;
        }
        File publicDir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if(publicDir!=null&&(publicDir.isDirectory()||publicDir.mkdirs()))return publicDir;
        return new File(primary==null?getFilesDir():primary,"Download");
    }
    private void downloadToSystemDownloads(ArrayList<Entry> files){
        if(files.isEmpty()||!onlyFiles(files))return;
        if(!fullStorageGranted()){requestFullAccess();return;}
        File folder=systemDownloadFolder();
        if(folder==null||!folder.isDirectory()){warn("系统 Download 目录不可用，请重新授予完整存储访问权限");return;}
        downloadFiles(files,folder);
    }
    private boolean onlyFiles(ArrayList<Entry> files){if(files.isEmpty())return false;for(Entry entry:files)if(entry.dir){toast("暂不支持文件夹递归传输，请进入目录选择文件");return false;}return true;}
    private void pasteNetwork(){ArrayList<Entry> files=new ArrayList<>(clipboard);if(!onlyFiles(files))return;
        if(moveClipboard){warn("跨设备移动暂不支持。\n请先复制，确认成功后再删除原文件。");return;}
        if(tab().remote()&&!clipboardProfile.isEmpty()){warn("远程到远程复制暂不支持。\n请先下载到手机，再上传到目标服务器");return;}
        if(tab().remote()){
            String id=tab().profileId,directory=tab().current();transfer("上传",id,session->{for(Entry file:files){checkCancelled();try(InputStream input=LocalTransferFiles.openUpload(getContentResolver(),file.uri)){if(input==null)throw new IOException("无法读取 "+file.name);session.upload(directory,file.name,input,n->progress(file.name,n));}}},()->{clipboard.clear();updateActions();});
        }else if(!tab().current().isEmpty())downloadFiles(files,new File(tab().current()));else toast("请先选择手机目标目录");
    }
    private void downloadFiles(ArrayList<Entry> files,File destination){if(!onlyFiles(files))return;String profileId=files.get(0).profileId;
        transfer("下载",profileId,session->{String source=transferSource(profileId);for(Entry file:files){checkCancelled();ensureNameAvailable(destination,file.name);
                    File target=new File(destination,file.name);
                    File partial=new File(destination,".cjfm-"+UUID.randomUUID()+".part");try{
                    try(OutputStream output=new FileOutputStream(partial)){session.download(file.uri,output,n->progress(file.name,n));}
                    checkCancelled();LocalTransferFiles.commitDownload(partial,target,this::checkCancelled);
                    /* 落盘后立刻记进「传输任务」页：用户从那里能一键跳到目录或直接打开，不必再找一遍。 */
                    TransferHistory.record(this,target,TransferHistory.KIND_SAVED,source+" · "+file.uri);
                }finally{partial.delete();}}
            },()->{clipboard.clear();updateActions();});
    }
    /** 传输来源描述（连接名 · 远程路径），列表里用来区分同一个文件名来自哪台机器。 */
    private String transferSource(String profileId){try{ConnectionStore.Profile profile=connections.get(profileId);return profile==null||profile.name==null?"":profile.name;}catch(Exception ignored){return "";}}
    private void remoteExternal(ArrayList<Entry> files,boolean open){if(open&&files.size()!=1){toast("打开方式只能选择一个文件");return;}if(!onlyFiles(files))return;
        new MaterialAlertDialogBuilder(this).setTitle(open?"下载并打开？":"下载并分享？").setMessage("先将 "+files.size()+" 个远程文件下载到本机临时缓存，再交给其他应用。所有文件完成后才会打开选择器。").setNegativeButton("取消",null).setPositiveButton("下载并继续",(d,w)->{
            ArrayList<Entry> prepared=new ArrayList<>();File directory=new File(getCacheDir(),"client-exports/"+UUID.randomUUID());
            transfer("准备文件",files.get(0).profileId,session->{if(!directory.mkdirs())throw new IOException("无法创建临时缓存");boolean complete=false;String source=transferSource(files.get(0).profileId);try{long expected=0;for(Entry file:files){if(file.size>0){if(Long.MAX_VALUE-expected<file.size)throw new IOException("文件总大小过大");expected+=file.size;}}if(expected>directory.getUsableSpace())throw new IOException("本机缓存空间不足");
                    for(Entry file:files){checkCancelled();File temp=new File(directory,UUID.randomUUID()+".part"),ready=new File(directory,file.name);try(OutputStream output=new FileOutputStream(temp)){session.download(file.uri,output,n->progress(file.name,n));}if(!temp.renameTo(ready))throw new IOException("无法提交临时文件");
                        TransferHistory.record(this,ready,TransferHistory.KIND_CACHED,source+" · "+file.uri);
                        Entry local=new Entry();local.uri=FileProvider.getUriForFile(this,getPackageName()+".client.exports",ready).toString();local.name=file.name;local.mime=file.mime;prepared.add(local);}complete=true;
                }finally{if(!complete){File[] leftovers=directory.listFiles();if(leftovers!=null)for(File f:leftovers)f.delete();directory.delete();}}},()->{external(prepared,open);});
        }).show();
    }

    /* ===== 列表 / 图标两种浏览样式 ===== */
    private boolean gridMode(){return prefs.getInt("view_mode",0)==1;}
    private void applyViewMode(){if(list==null)return;
        int horizontalPadding=dp(gridMode()?12:6);
        list.setPadding(horizontalPadding,list.getPaddingTop(),horizontalPadding,list.getPaddingBottom());
        boolean tablet=prefs.getBoolean("tablet_mode",false);
        list.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this,gridMode()?(tablet?5:3):(tablet?3:2)));
        list.invalidateItemDecorations();
        adapter.notifyDataSetChanged();}
    private void toggleViewMode(){prefs.edit().putInt("view_mode",gridMode()?0:1).apply();applyViewMode();adapter.notifyDataSetChanged();toast(gridMode()?"已切换为图标视图":"已切换为列表视图");}
    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.Holder>{
        private final ArrayList<Entry> filtered=new ArrayList<>();
        FileAdapter(){registerAdapterDataObserver(new RecyclerView.AdapterDataObserver(){@Override public void onChanged(){rebuild();}});}
        private void rebuild(){filtered.clear();TabState t=activeTab();String q=t==null||t.query==null?"":t.query.toLowerCase(Locale.ROOT);boolean hide=prefs.getBoolean("hide_hidden",true);for(Entry e:entries){if(e.name==null)continue;if(hide&&e.name.startsWith("."))continue;if(q.isEmpty()||e.name.toLowerCase(Locale.ROOT).contains(q))filtered.add(e);}}

        ArrayList<Entry> visible(){return filtered;}
        class Holder extends RecyclerView.ViewHolder{Holder(View v){super(v);}}
        @Override public int getItemViewType(int position){return gridMode()?1:0;}
        @NonNull public Holder onCreateViewHolder(@NonNull android.view.ViewGroup p,int type){return new Holder(getLayoutInflater().inflate(type==1?R.layout.client_file_grid:R.layout.client_file_row,p,false));}
        public int getItemCount(){return visible().size();}
        public void onBindViewHolder(@NonNull Holder h,int position){
            ArrayList<Entry> visible=visible();if(position<0||position>=visible.size())return;
            Entry e=visible.get(position);View v=h.itemView;boolean grid=gridMode();
            android.graphics.drawable.Drawable card=v.getBackground();if(card!=null)card.mutate().setAlpha(Math.round(Math.max(0,Math.min(100,prefs.getInt("row_alpha",70)))*2.55f));
            ((TextView)v.findViewById(R.id.client_file_name)).setText(e.name);
            String size=e.dir?"文件夹":Formatter.formatFileSize(MainActivity.this,e.size);
            ((TextView)v.findViewById(R.id.client_file_detail)).setText(grid?size:size+(e.time>0?" · "+DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(e.time)):""));
            ImageView icon=v.findViewById(R.id.client_file_icon);
            icon.setTag(R.id.client_file_icon,null);
            boolean wantThumb=!e.dir&&thumbAllowed(e);
            /* 类型图标自带颜色：vector 里直接引用 @color，随深浅色主题切换。
               这里绝不能 setColorFilter —— 那会把双色图标整块染成一个色，折角/后板层次全没。 */
            icon.clearColorFilter();
            if(e.dir||!wantThumb){
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setImageResource(e.dir?R.drawable.client_file_icon_folder:iconFor(e));
                icon.setForeground(null);
            }else{
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                icon.setImageResource(iconFor(e));
                requestThumb(e,icon);
                /* 视频缩略图右下角加三角形角标，和图片区分开；仅支持缩略图的视频文件有。 */
                icon.setForeground(mimeOf(e).startsWith("video/")?androidx.appcompat.content.res.AppCompatResources.getDrawable(MainActivity.this,R.drawable.client_thumb_video_badge):null);
            }
            boolean isSelected=selected.contains(e.uri);
            v.setActivated(isSelected);v.findViewById(R.id.client_file_selected).setVisibility(isSelected?View.VISIBLE:View.GONE);
            v.setContentDescription(e.name+(isSelected?"，已选择":""));
            v.setOnLongClickListener(x->{if(!selected.add(e.uri))selected.remove(e.uri);notifyDataSetChanged();updateActions();return true;});
            v.setOnClickListener(x->{if(!selected.isEmpty()){if(!selected.add(e.uri))selected.remove(e.uri);notifyDataSetChanged();updateActions();}else if(e.dir){tab().stack.add(new Place(e.uri,e.name));tab().scroll=0;renderTabs();load();}else openFile(e);});
        }
    }
}
