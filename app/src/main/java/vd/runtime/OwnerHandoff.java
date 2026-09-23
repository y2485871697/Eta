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
    static void rejectExistingPackage(String pkg)throws Exception {
        for(Object t:roots().values()) if(base(t).startsWith(pkg+"/"))throw new IllegalStateException("existing active task");
        Object slice=invokeAtm("getRecentTasks",new Class<?>[]{int.class,int.class,int.class},256,2,0);
        Object list=slice.getClass().getMethod("getList").invoke(slice);
        if(!(list instanceof List))throw new IllegalStateException("recent inventory unknown");
        for(Object t:(List<?>)list) {
            Intent intent=(Intent)field(t,"baseIntent");
            if(intent!=null&&intent.getComponent()!=null&&pkg.equals(intent.getComponent().getPackageName()))throw new IllegalStateException("existing recent task");
        }
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
                int id=number(t,"taskId");Task identity=owned.get(id);
                if(identity==null||selected.contains(id))throw new IllegalStateException("unexpected residual task");
                verifyDisplay(source,unique);identity.check(roots().get(id),source);focus(main);
                phase="remove:"+id;Object ok=invokeAtm("removeTask",new Class<?>[]{int.class},id);
                if(!Boolean.TRUE.equals(ok)||roots().containsKey(id))throw new IllegalStateException("remove not verified");removed.put(id);
            }
            verifyDisplay(source,unique);focus(main);
            for(Object t:roots().values())if(number(t,"displayId")==source)throw new IllegalStateException("source occupied");
            for(Integer id:selected)owned.get(id).check(roots().get(id),0);
            return new JSONObject().put("handedOff",true).put("sourceEmpty",true).put("keptTaskIds",moved).put("removedTaskIds",removed);
        } catch(Exception ex) {
            throw new OwnerException("HANDOFF_UNCERTAIN",phase+":"+ex.getClass().getSimpleName()+"; moved="+moved+" removed="+removed);
        }
    }
}
