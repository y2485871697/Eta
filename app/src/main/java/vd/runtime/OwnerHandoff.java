package vd.runtime;

import android.content.Intent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Restricted exclusive-session handoff. Not an atomic system_server admission fence. */
final class OwnerHandoff {
    static Object field(Object object, String name) throws Exception {
        if (object == null) throw new IllegalStateException("null field " + name);
        for (Class<?> c=object.getClass(); c!=null; c=c.getSuperclass()) {
            try { Field f=c.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    static int number(Object object,String key)throws Exception { return ((Integer)field(object,key)).intValue(); }
    static Object atm()throws Exception { return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null); }
    static Object invokeAtm(String method,Class<?>[] types,Object... args)throws Exception {
        return Class.forName("android.app.IActivityTaskManager").getMethod(method,types).invoke(atm(),args);
    }
    static Map<Integer,Object> roots()throws Exception {
        Object result=invokeAtm("getAllRootTaskInfos",new Class<?>[0]);
        if (!(result instanceof List)) throw new IllegalStateException("inventory type");
        Map<Integer,Object> out=new LinkedHashMap<Integer,Object>();
        for(Object t:(List<?>)result) if(out.put(number(t,"taskId"),t)!=null) throw new IllegalStateException("duplicate task");
        return out;
    }
    static Object binder(Object t)throws Exception {
        Object token=field(t,"token"); return token.getClass().getMethod("asBinder").invoke(token);
    }
    static String base(Object t)throws Exception {
        Intent i=(Intent)field(t,"baseIntent");
        if(i==null||i.getComponent()==null) throw new IllegalStateException("base intent");
        return i.getComponent().flattenToString();
    }
    /** Launch preflight: distinguish a real target task from an unreadable inventory. */
    static void rejectExistingPackage(String pkg)throws OwnerException {
        Map<Integer,Object> current;
        try { current=roots(); }
        catch(Exception ex) { throw new OwnerException(LaunchTargetOccupancy.UNKNOWN,
                "root inventory " + ex.getClass().getSimpleName()); }
        List<LaunchTargetOccupancy.Root> rootDescriptions=new ArrayList<LaunchTargetOccupancy.Root>();
        for(Object task:current.values()) rootDescriptions.add(describeRoot(task));
        // Report an observed active target even if the recent-task query later fails.
        LaunchTargetOccupancy.Decision active=LaunchTargetOccupancy.decide(pkg,
                rootDescriptions, Collections.<LaunchTargetOccupancy.Recent>emptyList());
        if(LaunchTargetOccupancy.ACTIVE.equals(active.code)) throw new OwnerException(active.code,active.detail);
        List<LaunchTargetOccupancy.Recent> recentDescriptions=new ArrayList<LaunchTargetOccupancy.Recent>();
        try {
            Object slice=invokeAtm("getRecentTasks",new Class<?>[]{int.class,int.class,int.class},256,1,0);
            Object list=slice.getClass().getMethod("getList").invoke(slice);
            if(!(list instanceof List))throw new IllegalStateException("recent inventory type");
            List<?> entries=(List<?>)list;
            // A full page does not prove that older target tasks are absent.
            for(Object task:entries) recentDescriptions.add(describeRecent(task));
        }
        catch(Exception ex) { throw new OwnerException(LaunchTargetOccupancy.UNKNOWN,
                "recent inventory " + ex.getClass().getSimpleName()); }
        LaunchTargetOccupancy.Decision result=LaunchTargetOccupancy.decide(pkg,rootDescriptions,recentDescriptions);
        if(LaunchTargetOccupancy.RECENT.equals(result.code)) throw new OwnerException(result.code,result.detail);
        // A full page may omit older target tasks; refuse even if the visible page is clear.
        if(recentDescriptions.size()>=256) throw new OwnerException(LaunchTargetOccupancy.UNKNOWN,
                "recent inventory truncated");
        if(result.rejects()) throw new OwnerException(result.code,result.detail);
    }

    private static String componentPackage(Object value) throws Exception {
        if(value==null)return null;
        if(!(value instanceof android.content.ComponentName))throw new IllegalStateException("component type");
        return ((android.content.ComponentName)value).getPackageName();
    }
    private static String intentPackage(Object task) throws Exception {
        Object value=field(task,"baseIntent");
        if(value==null)return null;
        if(!(value instanceof Intent))throw new IllegalStateException("intent type");
        Intent intent=(Intent)value;
        String component=componentPackage(intent.getComponent());
        return component!=null ? component : intent.getPackage();
    }
    private static LaunchTargetOccupancy.Root describeRoot(Object task) {
        int id=-1, activities=-1;
        try { id=number(task,"taskId"); } catch(Exception ignored) { }
        try { activities=number(task,"numActivities"); } catch(Exception ignored) { }
        String base=null,baseActivity=null,topActivity=null,realActivity=null,origActivity=null;
        boolean componentsKnown=false;
        try {
            base=intentPackage(task);
            baseActivity=componentPackage(field(task,"baseActivity"));
            topActivity=componentPackage(field(task,"topActivity"));
            realActivity=componentPackage(field(task,"realActivity"));
            origActivity=componentPackage(field(task,"origActivity"));
            componentsKnown=true;
        } catch(Exception ignored) { }
        boolean childIdsKnown=false,childNamesKnown=false;
        int[] childIds=null; String[] childNames=null;
        try {
            int[] ids=(int[])field(task,"childTaskIds");
            if(ids!=null && id>=0) {
                childIdsKnown=true; childIds=ids;
                String[] names=childPackages(task,ids);
                if(names!=null && names.length==ids.length) { childNames=names; childNamesKnown=true; }
            }
        } catch(Exception ignored) { }
        return new LaunchTargetOccupancy.Root(id,base,baseActivity,topActivity,realActivity,
                origActivity,componentsKnown,activities,childIdsKnown,childIds,childNamesKnown,childNames);
    }
    /**
     * Package name for every platform child task id, or {@code null} when a child cannot be named.
     *
     * <p>Prefers the parallel {@code childTaskNames} array the platform already carries next to
     * {@code childTaskIds} and only falls back to {@code getTaskInfo} when that array is missing or
     * not parallel. A child the platform does not name stays {@code null}, so the pure policy fails
     * closed instead of treating an unnamed child as harmless.
     */
    private static String[] childPackages(Object task,int[] ids) {
        try {
            Object raw=field(task,"childTaskNames");
            if(raw instanceof String[] && ((String[])raw).length==ids.length) {
                String[] names=(String[])raw;
                String[] out=new String[ids.length];
                for(int i=0;i<ids.length;i++) out[i]=childNamePackage(names[i]);
                return out;
            }
        } catch(Exception ignored) { }
        return resolveChildPackages(ids);
    }
    /**
     * Package of a flattened child component ({@code pkg/Class}) or a bare package string; a blank,
     * empty or non-identifier value is reported as {@code null} (identity unknown), never guessed.
     */
    static String childNamePackage(String raw) {
        if(raw==null) return null;
        int slash=raw.indexOf('/');
        String pkg=slash<0 ? raw : raw.substring(0,slash);
        if(pkg.isEmpty()) return null;
        return OwnerProtocol.isSafeIdentifier(pkg) ? pkg : null;
    }
    private static String[] resolveChildPackages(int[] ids) {
        Object service; Method lookup; boolean twoArg;
        try {
            service=atm();
            Class<?> iface=Class.forName("android.app.IActivityTaskManager");
            Method two=null;
            try { two=iface.getMethod("getTaskInfo",int.class,boolean.class); } catch(Exception ignored) { }
            Method one=null;
            if(two==null) try { one=iface.getMethod("getTaskInfo",int.class); } catch(Exception ignored) { }
            lookup=two!=null?two:one; twoArg=two!=null;
            if(lookup==null) return null;
        } catch(Exception missing) { return null; }
        String[] out=new String[ids.length];
        for(int i=0;i<ids.length;i++) {
            Object info=null;
            try {
                info=twoArg ? lookup.invoke(service,Integer.valueOf(ids[i]),Boolean.FALSE)
                        : lookup.invoke(service,Integer.valueOf(ids[i]));
            } catch(Exception ignored) { }
            out[i]=childTaskPackage(info);
        }
        return out;
    }
    private static String childTaskPackage(Object info) {
        if(info==null) return null;
        try { String pkg=intentPackage(info); if(pkg!=null) return pkg; } catch(Exception ignored) { }
        try { return componentPackage(field(info,"topActivity")); } catch(Exception ignored) { return null; }
    }
    private static LaunchTargetOccupancy.Recent describeRecent(Object task) {
        String base=null,baseActivity=null,topActivity=null,realActivity=null,origActivity=null;
        boolean known=false;
        try {
            base=intentPackage(task);
            baseActivity=componentPackage(field(task,"baseActivity"));
            topActivity=componentPackage(field(task,"topActivity"));
            realActivity=componentPackage(field(task,"realActivity"));
            origActivity=componentPackage(field(task,"origActivity"));
            known=true;
        } catch(Exception ignored) { }
        return new LaunchTargetOccupancy.Recent(base,baseActivity,topActivity,realActivity,origActivity,known);
    }
    static void verifyDisplay(int id,String unique)throws Exception {
        Class<?> c=Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object g=c.getMethod("getInstance").invoke(null);
        Object info=c.getMethod("getDisplayInfo",int.class).invoke(g,id);
        if(info==null||!unique.equals(field(info,"uniqueId"))) throw new IllegalStateException("display identity changed");
    }
    static final class Task {
        final int id; final Object binder; final String base; final String data;
        Task(Object t)throws Exception { id=number(t,"taskId");binder=binder(t);base=base(t);data=((Intent)field(t,"baseIntent")).getDataString(); }
        void check(Object t,int display)throws Exception {
            if(t==null||!Objects.equals(data,((Intent)field(t,"baseIntent")).getDataString())||number(t,"taskId")!=id||number(t,"displayId")!=display||!binder.equals(binder(t))||!base.equals(base(t))) throw new IllegalStateException("task identity changed");
            if(number(t,"userId")!=0||number(t,"parentTaskId")!=-1) throw new IllegalStateException("unsupported task shape");
            int[] kids=(int[])field(t,"childTaskIds");
            if(kids==null||kids.length!=1||kids[0]!=id) throw new IllegalStateException("unsupported nested task");
        }
    }
    static void tx(Object t, boolean hide, boolean restore)throws Exception {
        Class<?> cl=Class.forName("android.window.WindowContainerTransaction"); Object change=cl.getConstructor().newInstance();
        Class<?> tokenCl=Class.forName("android.window.WindowContainerToken");Object token=field(t,"token");
        if(hide||restore){cl.getMethod("setHidden",tokenCl,boolean.class).invoke(change,token,hide);cl.getMethod("setFocusable",tokenCl,boolean.class).invoke(change,token,!hide);}
        if(!hide) cl.getMethod("reorder",tokenCl,boolean.class).invoke(change,token,false);
        Class<?> org=Class.forName("android.window.WindowOrganizer");org.getMethod("applyTransaction",cl).invoke(org.getConstructor().newInstance(),change);
    }
    static void focus(Task main)throws Exception {
        Object f=invokeAtm("getFocusedRootTaskInfo",new Class<?>[0]);main.check(f,0);
    }
    static JSONObject move(int source,String unique,Map<Integer,Task> owned,JSONArray keep)throws OwnerException {
        JSONArray moved=new JSONArray(),removed=new JSONArray(); String phase="preflight";
        try {
            verifyDisplay(source,unique);
            Set<Integer> selected=new LinkedHashSet<Integer>();
            if(keep==null) throw new IllegalStateException("taskIds array required");
            for(int i=0;i<keep.length();i++) {
                Object n=keep.get(i);if(!(n instanceof Integer)||!selected.add((Integer)n)||!owned.containsKey((Integer)n)) throw new IllegalStateException("invalid taskIds");
            }
            Map<Integer,Object> current=roots();
            for(Object t:current.values()) if(number(t,"displayId")==source) {
                Task identity=owned.get(number(t,"taskId"));if(identity==null)throw new IllegalStateException("foreign source task");identity.check(t,source);
            }
            for(Integer id:selected) owned.get(id).check(current.get(id),source);
            Task main=new Task(invokeAtm("getFocusedRootTaskInfo",new Class<?>[0]));main.check(current.get(main.id),0);
            // A dedicated source-display cover prevents migrating the currently focused source task.
            String anchorUri="eta-vd-anchor://handoff/"+UUID.randomUUID().toString();
            String anchorComponent="io.github.mangi.eta/io.github.mangi.eta.agent.device.VirtualDisplayAnchorActivity";
            OwnerShell.Result started=OwnerShell.run(new String[]{"/system/bin/am","start","--display",Integer.toString(source),"-n",anchorComponent,"-d",anchorUri,"-f",Integer.toString(0x18000000)},10000L,8192);
            if(!started.success())throw new IllegalStateException("anchor launch failed");
            Task anchor=null;
            for(int attempt=0;attempt<20 && anchor==null;attempt++) {
                for(Object t:roots().values())if(number(t,"displayId")==source && !current.containsKey(number(t,"taskId"))) {
                    Intent bi=(Intent)field(t,"baseIntent");
                    if(anchorUri.equals(bi.getDataString()) && bi.getComponent()!=null && anchorComponent.equals(bi.getComponent().flattenToString())) {
                        Task found=new Task(t);found.check(t,source);
                        if(anchor!=null)throw new IllegalStateException("multiple anchors");anchor=found;
                    }
                }
                if(anchor==null)Thread.sleep(100L);
            }
            if(anchor==null)throw new IllegalStateException("anchor identity not observed");
            focus(main);
            // Fresh tasks only. Default hidden/focusable restoration is an explicit limited-mode assumption.
            for(Integer id:selected) {
                Task identity=owned.get(id);verifyDisplay(source,unique);focus(main);
                Object t=roots().get(id);identity.check(t,source);
                phase="hide:"+id;tx(t,true,false);
                t=roots().get(id);identity.check(t,source);focus(main);
                phase="move:"+id;invokeAtm("moveRootTaskToDisplay",new Class<?>[]{int.class,int.class},id.intValue(),0);
                t=roots().get(id);identity.check(t,0);focus(main);
                phase="park:"+id;tx(t,false,false);t=roots().get(id);identity.check(t,0);focus(main);
                phase="restore:"+id;tx(t,false,true);identity.check(roots().get(id),0);focus(main);moved.put(id);
            }
            for(Object t:roots().values()) if(number(t,"displayId")==source) {
                int id=number(t,"taskId");
                if(id==anchor.id)continue;
                Task identity=owned.get(id);
                if(identity==null||selected.contains(id))throw new IllegalStateException("unexpected residual task");
                verifyDisplay(source,unique);identity.check(roots().get(id),source);focus(main);
                phase="remove:"+id;Object ok=invokeAtm("removeTask",new Class<?>[]{int.class},id);
                if(!Boolean.TRUE.equals(ok)||roots().containsKey(id))throw new IllegalStateException("remove not verified");removed.put(id);
            }
            verifyDisplay(source,unique);focus(main);
            anchor.check(roots().get(anchor.id),source);
            phase="remove-anchor";
            if(!Boolean.TRUE.equals(invokeAtm("removeTask",new Class<?>[]{int.class},anchor.id)))throw new IllegalStateException("anchor removal failed");
            for(int attempt=0;attempt<20 && roots().containsKey(anchor.id);attempt++)Thread.sleep(100L);
            if(roots().containsKey(anchor.id))throw new IllegalStateException("anchor removal uncertain");
            verifyDisplay(source,unique);focus(main);
            for(Object t:roots().values())if(number(t,"displayId")==source)throw new IllegalStateException("source occupied");
            for(Integer id:selected)owned.get(id).check(roots().get(id),0);
            return new JSONObject().put("handedOff",true).put("sourceEmpty",true).put("keptTaskIds",moved).put("removedTaskIds",removed);
        } catch(Exception ex) {
            throw new OwnerException("HANDOFF_UNCERTAIN",phase+":"+ex.getClass().getSimpleName()+"; moved="+moved+" removed="+removed);
        }
    }
}
