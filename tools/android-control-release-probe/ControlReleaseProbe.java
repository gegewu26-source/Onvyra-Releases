package me.rerere.androidcontrol.releaseprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Framework-only: a Debug AndroidJUnitRunner cannot link against an independently R8-shrunk APK. */
public final class ControlReleaseProbe extends Instrumentation {
    private Bundle arguments;
    private String lastActivePackage="<none>";
    private boolean sawOwnWindow;
    @Override public void onCreate(Bundle args) {super.onCreate(args);arguments=args;start();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            require(Build.VERSION.SDK_INT>=34 && (Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish")),"Not a lab emulator");
            require("StatsUsageTest".equals(shellText("getprop ro.boot.qemu.avd_name").trim()),"Not the dedicated lab AVD");
            AccessibilityServiceInfo automation=getUiAutomation().getServiceInfo();
            require(automation!=null,"UiAutomation did not connect");
            automation.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(automation);
            Context context=getTargetContext();
            require(context.getPackageName().equals("me.rerere.rikkahub.debug"),"Wrong target");
            require((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)==0,"Target must be the signed R8 APK");
            String mode=arguments.getString("mode","");
            require(mode.equals("bootstrap") || mode.equals("auth"),"Unknown mode");
            context.startActivity(new Intent().setClassName(context.getPackageName(),
                "me.rerere.androidcontrol.ui.AndroidControlActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            selectWirelessMode();
            if(mode.equals("bootstrap")) {
                scrollToTop();
                AccessibilityNodeInfo master=await("允许 AI 操作这台手机",5000);
                AccessibilityNodeInfo toggle=checkable(master);
                require(toggle!=null,"Own opt-in control did not expose its checked state");
                if(!toggle.isChecked())click(toggle);
                click(await("复制USB命令",10000));
                ClipboardManager clipboard=context.getSystemService(ClipboardManager.class);
                String command=clipboard.getPrimaryClip()==null?"":clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                require(command.startsWith("adb shell sh /sdcard/Android/data/me.rerere.rikkahub.debug/"),"Missing USB command");
                result.putString("usb_command",command); // Path only; never read or print the script's nonce.
            } else {
                scrollToTop();
                awaitBridgeStatus("已连接",20000);
                // The production UI only displays connected when actual helper.identity()[0] == 2000.
                result.putInt("verified_shell_uid",2000);
                result.putBoolean("reauthenticated_after_process_restart",true);
                click(await("立即停止",5000,false));
                await("已请求立即停止所有手机操作。",5000,false);
                result.putBoolean("stop_control_feedback_verified",true);
                click(await("撤销配对",10000));
                scrollToTop();
                AccessibilityNodeInfo master=await("允许 AI 操作这台手机",5000);
                AccessibilityNodeInfo toggle=checkable(master);
                require(toggle!=null,"Own opt-in control did not expose its checked state");
                if(toggle.isChecked())click(toggle);
                scrollToTop();
                awaitBridgeStatus("断开",5000);
                result.putBoolean("local_authorization_revoked",true);
            }
            result.putString("mode",mode);result.putString("status","PASS");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("status","FAIL");result.putString("error",error.toString());
            result.putString("last_active_root_package",lastActivePackage);
            result.putBoolean("saw_target_window",sawOwnWindow);
            java.io.StringWriter trace=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("trace",trace.toString());finish(Activity.RESULT_CANCELED,result);
        }
    }
    private AccessibilityNodeInfo await(String text,long timeout) {
        return await(text,timeout,true);
    }
    private void selectWirelessMode() {
        long deadline=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<deadline) {
            AccessibilityNodeInfo root=ownRoot();
            if(root!=null) {
                AccessibilityNodeInfo back=findDescription(root,"选择操控模式",0,new int[]{2000});
                if(back!=null) {
                    click(back);
                    click(await("无线调试模式",5000,false));
                    await("操控状态",5000,false);
                    return;
                }
                AccessibilityNodeInfo wireless=findText(root,"无线调试模式",0,new int[]{2000});
                if(wireless!=null) {
                    click(wireless);
                    await("操控状态",5000,false);
                    return;
                }
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("Could not enter the explicit wireless setup branch");
    }
    private AccessibilityNodeInfo findDescription(AccessibilityNodeInfo node,String text,int depth,int[] remaining) {
        if(node==null || depth>50 || remaining[0]--<=0)return null;
        if("me.rerere.rikkahub.debug".contentEquals(node.getPackageName())
            && node.getContentDescription()!=null
            && text.contentEquals(node.getContentDescription()))return node;
        for(int i=0;i<node.getChildCount() && remaining[0]>0;i++) {
            AccessibilityNodeInfo found=findDescription(node.getChild(i),text,depth+1,remaining);
            if(found!=null)return found;
        }
        return null;
    }
    private void awaitBridgeStatus(String text,long timeout) {
        long deadline=SystemClock.uptimeMillis()+timeout;
        while(SystemClock.uptimeMillis()<deadline) {
            AccessibilityNodeInfo root=ownRoot();
            if(root!=null) {
                AccessibilityNodeInfo heading=findText(root,"操控状态",0,new int[]{2000});
                AccessibilityNodeInfo next=findText(root,"授权设置",0,new int[]{2000});
                if(heading!=null && next!=null) {
                    Rect top=new Rect(),bottom=new Rect();
                    heading.getBoundsInScreen(top);next.getBoundsInScreen(bottom);
                    // Both the bridge and accessibility rows can say connected. Only
                    // accept the bridge status inside its own first settings group.
                    if(findStatusText(root,text,top.bottom,bottom.top,0,new int[]{2000}))return;
                }
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("Timed out waiting for bridge status in 操控状态: "+text);
    }
    private boolean findStatusText(AccessibilityNodeInfo node,String text,int top,int bottom,int depth,int[] remaining) {
        if(node==null || depth>50 || remaining[0]--<=0)return false;
        if("me.rerere.rikkahub.debug".contentEquals(node.getPackageName())
            && node.getText()!=null && text.equals(node.getText().toString())) {
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            if(!bounds.isEmpty() && bounds.centerY()>=top && bounds.centerY()<bottom)return true;
        }
        for(int i=0;i<node.getChildCount() && remaining[0]>0;i++)
            if(findStatusText(node.getChild(i),text,top,bottom,depth+1,remaining))return true;
        return false;
    }
    private AccessibilityNodeInfo await(String text,long timeout,boolean revealByScrolling) {
        long deadline=SystemClock.uptimeMillis()+timeout;
        while(SystemClock.uptimeMillis()<deadline) {
            AccessibilityNodeInfo root=ownRoot();
            if(root!=null) {
                // Compose exposes virtual descendants but its provider need not implement
                // findAccessibilityNodeInfosByText. Read the same semantics nodes directly.
                AccessibilityNodeInfo found=findText(root,text,0,new int[]{2000});
                if(found!=null)return found;
                // LazyColumn only exposes composed items. Scroll the app's own list to
                // reveal setup actions instead of relying on the former plain View layout.
                if(revealByScrolling) {
                    AccessibilityNodeInfo list=scrollable(root);
                    if(list!=null)list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                }
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("Timed out waiting for own setup UI: "+text);
    }
    private AccessibilityNodeInfo findText(AccessibilityNodeInfo node,String text,int depth,int[] remaining) {
        if(node==null || depth>50 || remaining[0]--<=0)return null;
        if("me.rerere.rikkahub.debug".contentEquals(node.getPackageName())
            && node.getText()!=null && node.getText().toString().contains(text))return node;
        for(int i=0;i<node.getChildCount() && remaining[0]>0;i++) {
            AccessibilityNodeInfo found=findText(node.getChild(i),text,depth+1,remaining);
            if(found!=null)return found;
        }
        return null;
    }
    private void click(AccessibilityNodeInfo node) {
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        int depth=0;
        for(AccessibilityNodeInfo target=node;target!=null && depth++<50;target=target.getParent()) {
            if(target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;
        }
        throw new AssertionError("Own setup control did not accept click");
    }
    private AccessibilityNodeInfo checkable(AccessibilityNodeInfo node) {
        int depth=0;
        for(AccessibilityNodeInfo target=node;target!=null && depth++<50;target=target.getParent()) {
            if(target.isCheckable())return target;
            AccessibilityNodeInfo child=checkableChild(target);
            if(child!=null)return child;
        }
        return null;
    }
    private AccessibilityNodeInfo checkableChild(AccessibilityNodeInfo node) {
        return checkableChild(node,0,new int[]{2000});
    }
    private AccessibilityNodeInfo checkableChild(AccessibilityNodeInfo node,int depth,int[] remaining) {
        if(node==null || depth>50 || remaining[0]--<=0)return null;
        if(node.isCheckable())return node;
        for(int i=0;i<node.getChildCount() && remaining[0]>0;i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null) {AccessibilityNodeInfo result=checkableChild(child,depth+1,remaining);if(result!=null)return result;}
        }
        return null;
    }
    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
        return scrollable(node,0,new int[]{2000});
    }
    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node,int depth,int[] remaining) {
        if(node==null || depth>50 || remaining[0]--<=0)return null;
        if(node.isScrollable())return node;
        for(int i=0;i<node.getChildCount() && remaining[0]>0;i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null) {AccessibilityNodeInfo result=scrollable(child,depth+1,remaining);if(result!=null)return result;}
        }
        return null;
    }
    private void scrollToTop() {
        for(int i=0;i<12;i++) {
            AccessibilityNodeInfo root=ownRoot();
            if(root==null)return;
            AccessibilityNodeInfo list=scrollable(root);
            if(list==null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return;
            SystemClock.sleep(120);
        }
    }
    private AccessibilityNodeInfo ownRoot() {
        AccessibilityNodeInfo active=getUiAutomation().getRootInActiveWindow();
        lastActivePackage=active==null?"<none>":String.valueOf(active.getPackageName());
        if(active!=null && "me.rerere.rikkahub.debug".contentEquals(active.getPackageName())) {
            sawOwnWindow=true;
            return active;
        }
        // A redirect or focus transition can leave the previous activity as the active
        // accessibility root. UiAutomator searches interactive windows too; do likewise,
        // but only on the main display and only for this target package.
        for(AccessibilityWindowInfo window:getUiAutomation().getWindows()) {
            if(window.getDisplayId()!=0)continue;
            AccessibilityNodeInfo root=window.getRoot();
            if(root!=null && "me.rerere.rikkahub.debug".contentEquals(root.getPackageName())) {
                sawOwnWindow=true;
                return root;
            }
        }
        return null;
    }
    private String shellText(String command) throws Exception {
        try(ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand(command);
            InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[1024];int count;
            while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
