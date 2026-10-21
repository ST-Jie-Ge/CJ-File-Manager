import io

p = 'FormatFactoryActivity.java'
s = io.open(p, encoding='utf-8').read()

# 删掉 renderOptions（选项药丸的条件分支已搬到 Compose 里）
start = s.index("    private void renderOptions(){")
end = s.index("    private String[] codecNames(){")
s = s[:start] + s[end:]

# 删掉三个只被旧视图层用到的方法：typeTile / renderTypePicker / renderActionCard
for a, b in [
    ("    private View typeTile(String title,String subtitle,int icon,int type){", "    private String[] codecNames(){"),
    ("    /** 首屏只做媒体类别选择，避免把视频和音频的输出能力混在同一组设置里。 */\n    private void renderTypePicker(){", "    private String describe(){"),
    ("    /** 三个动作（选择文件 / 输出目录 / 转换历史）单独一张卡片，四周留出更宽松的间距。 */\n    private void renderActionCard(){", "    private View rowButton(String label,Runnable click){"),
]:
    i = s.index(a); j = s.index(b)
    if j > i:
        s = s[:i] + s[j:]

# start()：改推状态
s = s.replace(
    "        running=true;startedAt=android.os.SystemClock.elapsedRealtime();\n"
    "        resultArea.setVisibility(View.GONE);progressArea.setVisibility(View.VISIBLE);progressArea.removeAllViews();\n"
    "        bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);\n"
    "        bar.setMax(100);bar.setProgress(0);progressArea.addView(bar,new LinearLayout.LayoutParams(-1,dp(6)));\n"
    "        statusText=text(\"准备中…\",12);statusText.setPadding(0,dp(8),0,0);\n"
    "        statusText.setTextColor(MusicControls.color(this,R.attr.client_text_secondary));progressArea.addView(statusText);\n"
    "        progressArea.addView(button(\"取消转换\",this::cancel));\n"
    "        startButton.setEnabled(false);startButton.setAlpha(.4f);\n"
    "        lastUi=0;",
    "        running=true;startedAt=android.os.SystemClock.elapsedRealtime();\n"
    "        uiState.setResult(FormatResult.NONE);uiState.setRunning(true);\n"
    "        uiState.setProgress(0);uiState.setStatus(\"准备中…\");uiState.bump();\n"
    "        lastUi=0;")

# updateProgress()
s = s.replace("        if(!running||bar==null||statusText==null)return;",
              "        if(!running||uiState==null)return;")
s = s.replace("            bar.setIndeterminate(false);bar.setProgress(percent);\n",
              "            uiState.setProgress(percent);\n")
s = s.replace("            bar.setIndeterminate(true);\n",
              "            uiState.setProgress(-1);\n")
s = s.replace("        statusText.setText(b.toString());\n    }",
              "        uiState.setStatus(b.toString());uiState.bump();\n    }")

# finishRun()
s = s.replace(
    "        running=false;startButton.setEnabled(true);startButton.setAlpha(1f);\n"
    "        progressArea.setVisibility(View.GONE);\n"
    "        if(!ok){\n"
    "            resultArea.setVisibility(View.VISIBLE);resultArea.removeAllViews();\n"
    "            TextView t=text(cancelled?\"已取消转换\":\"转换失败\",14);t.setTypeface(MusicStyle.LABEL);\n"
    "            t.setTextColor(MusicControls.color(this,R.attr.client_text));t.setPadding(dp(14),dp(12),dp(14),dp(4));resultArea.addView(t);\n"
    "            if(!cancelled&&stack!=null&&!stack.isEmpty()){\n"
    "                TextView detail=text(tail(stack),11);detail.setTextColor(MusicControls.color(this,R.attr.client_text_secondary));\n"
    "                detail.setPadding(dp(14),0,dp(14),dp(8));resultArea.addView(detail);\n"
    "            }\n"
    "            if(output!=null&&output.exists()&&!ok)output.delete();\n"
    "            return;\n"
    "        }",
    "        running=false;uiState.setRunning(false);\n"
    "        if(!ok){\n"
    "            uiState.setResult(FormatResult.FAILED);\n"
    "            uiState.setResultTitle(cancelled?\"已取消转换\":\"转换失败\");\n"
    "            uiState.setResultDetail(!cancelled&&stack!=null&&!stack.isEmpty()?tail(stack):\"\");\n"
    "            uiState.bump();\n"
    "            if(output!=null&&output.exists())output.delete();\n"
    "            return;\n"
    "        }")
s = s.replace(
    "        resultArea.setVisibility(View.VISIBLE);resultArea.removeAllViews();\n"
    "        TextView head=text(\"转换完成\",14);head.setTypeface(MusicStyle.LABEL);head.setTextColor(MusicControls.color(this,R.attr.client_text));\n"
    "        head.setPadding(dp(14),dp(12),dp(14),dp(2));resultArea.addView(head);\n"
    "        resultText=text(\"\",12);resultText.setTextColor(MusicControls.color(this,R.attr.client_text_secondary));\n"
    "        resultText.setPadding(dp(14),0,dp(14),dp(8));\n"
    "        resultText.setText(output.getName()+\"  ·  \"+(output.exists()?android.text.format.Formatter.formatFileSize(this,output.length()):\"—\")+\"  ·  用时 \"+seconds+\" 秒\");\n"
    "        resultArea.addView(resultText);\n"
    "        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setPadding(dp(10),0,dp(10),dp(10));\n"
    "        actions.addView(button(\"播放\",this::play,true),new LinearLayout.LayoutParams(0,-2,1));\n"
    "        actions.addView(button(\"分享\",this::share,true),new LinearLayout.LayoutParams(0,-2,1));\n"
    "        resultArea.addView(actions);",
    "        uiState.setResult(FormatResult.DONE);\n"
    "        uiState.setResultTitle(\"转换完成\");\n"
    "        uiState.setResultDetail(output.getName()+\"  ·  \"+(output.exists()?android.text.format.Formatter.formatFileSize(this,output.length()):\"—\")+\"  ·  用时 \"+seconds+\" 秒\");\n"
    "        uiState.bump();")

io.open(p, 'w', encoding='utf-8').write(s)
print("ok")
