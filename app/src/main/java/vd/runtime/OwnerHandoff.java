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
                String[] names=effectiveChildNames(id,ids,rawChildNames(task));
                if(names!=null && names.length==ids.length) { childNames=names; childNamesKnown=true; }
            }
        } catch(Exception ignored) { }
        // Organizer evidence is only consulted for an identity-free, empty, activityType-0 container.
        boolean emptyOrganizerProven=false, organizerEvidenceValid=false;
        if(childIdsKnown && componentsKnown && base==null && baseActivity==null && topActivity==null
                && realActivity==null && origActivity==null && activities==0) {
            Integer type=activityType(task);
            if(type!=null && type.intValue()==0) {
                organizerEvidenceValid=true;
                emptyOrganizerProven=organizerEmptyProven(task,childIds);
            }
        }
        return new LaunchTargetOccupancy.Root(id,base,baseActivity,topActivity,realActivity,
                origActivity,componentsKnown,activities,childIdsKnown,childIds,childNamesKnown,childNames,
                emptyOrganizerProven,organizerEvidenceValid);
    }
    /** The raw {@code childTaskNames} platform array for a task, or {@code null} when absent. */
    private static String[] rawChildNames(Object task) {
        try {
            Object raw=field(task,"childTaskNames");
            return raw instanceof String[] ? (String[])raw : null;
        } catch(Exception ignored) { return null; }
    }
    /**
     * Effective parallel child-name array for a root's raw child ids.
     *
     * <p>Only the platform's parallel {@code childTaskNames} array is trusted: a missing array, a
     * non-parallel array or an empty name list in the presence of ids leaves every child unnamed and
     * the caller must fail closed. When there is no foreign child id (only the root's own id or the
     * {@code -1} non-task marker) the raw names carry no child identity at all, so a same-length
     * all-null array is returned: this records "no named foreign child" without inventing a package
     * name. A {@code getTaskInfo} fallback is deliberately not used: it re-reads unrelated task state
     * by a raw id and cannot be tied back to this root. Pure; no device access.
     */
    static String[] effectiveChildNames(int selfId,int[] ids,String[] rawNames) {
        if(ids==null) return null;
        String[] names=parallelChildPackages(rawNames,ids);
        if(names!=null) return names;
        if(!LaunchTargetOccupancy.hasForeignChild(selfId,ids)) return new String[ids.length];
        return null;
    }
    /**
     * Packages for each id from the platform's parallel {@code childTaskNames} array, or {@code null}
     * when the array is absent, not parallel or empty while ids are present. An empty id list pairs
     * only with an absent or empty name array; an empty id list alongside a non-empty name array is
     * not a parallel association. Pure; no device access.
     */
    static String[] parallelChildPackages(String[] rawNames,int[] ids) {
        if(ids==null) return null;
        if(ids.length==0) return rawNames==null||rawNames.length==0 ? new String[0] : null;
        if(rawNames==null||rawNames.length!=ids.length) return null;
        String[] out=new String[ids.length];
        for(int i=0;i<ids.length;i++) out[i]=childNamePackage(rawNames[i]);
        return out;
    }
    /**
     * Package of a trusted platform child descriptor: a bare package name or a flattened
     * {@code pkg/Class} component. The platform's {@code RootTaskInfo.childTaskNames} placeholder
     * {@code "unknown"}, a blank value, a value carrying whitespace or control characters, and a
     * flattened component with a missing package or class part are all unreadable identities and are
     * reported as {@code null}, never guessed.
     */
    static String childNamePackage(String raw) {
        if(raw==null) return null;
        int slash=raw.indexOf('/');
        String pkg=slash<0 ? raw : raw.substring(0,slash);
        String cls=slash<0 ? null : raw.substring(slash+1);
        if(pkg.isEmpty()||isUnknownName(pkg)||!isPackageName(pkg)) return null;
        if(cls!=null&&!isComponentClass(cls)) return null;
        return pkg;
    }
    /** The literal placeholder the platform uses for a child task it cannot name. */
    private static boolean isUnknownName(String value) {
        return "unknown".equalsIgnoreCase(value);
    }
    /** Dotted identifier with no whitespace, slash, colon, control character or empty segment. */
    private static boolean isPackageName(String value) {
        if(value==null||value.isEmpty()) return false;
        boolean segmentStart=true;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c=='.') { if(segmentStart) return false; segmentStart=true; }
            else if((c>='a'&&c<='z')||(c>='A'&&c<='Z')||c=='_') segmentStart=false;
            else if(c>='0'&&c<='9'&&!segmentStart) { }
            else return false;
        }
        return !segmentStart;
    }
    /** Flattened component class: an optional short {@code .Class} prefix in front of a dotted name. */
    private static boolean isComponentClass(String value) {
        if(value==null||value.isEmpty()) return false;
        String body=value.charAt(0)=='.' ? value.substring(1) : value;
        return !body.isEmpty()&&isClassName(body);
    }
    private static boolean isClassName(String value) {
        boolean segmentStart=true;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c=='.') { if(segmentStart) return false; segmentStart=true; }
            else if((c>='a'&&c<='z')||(c>='A'&&c<='Z')||c=='_'||c=='$') segmentStart=false;
            else if(c>='0'&&c<='9'&&!segmentStart) { }
            else return false;
        }
        return !segmentStart;
    }
    /**
     * True when {@code childIds} is a complete identity of the root's nested tasks: not empty, every
     * id positive, no duplicates and never the root's own id. Pure; no device access.
     */
    static boolean completeChildIds(int[] childIds,int selfId) {
        if(childIds==null||childIds.length==0) return false;
        Set<Integer> seen=new HashSet<Integer>();
        for(int id:childIds) {
            if(id<=0||id==selfId||!seen.add(id)) return false;
        }
        return true;
    }
    /**
     * Read-only proof that {@code root} is an organizer-created, identity-free empty container whose
     * direct children are exactly {@code childIds}, each also an identity-free empty task.
     *
     * <p>Reflection only: an {@code android.window.TaskOrganizer} instance exposes {@code
     * getChildTasks(root.token, null)}, which returns the direct {@code RunningTaskInfo}s of a task
     * created by a {@code TaskOrganizer} and {@code null} when the task was not created by an
     * organizer ({@code mCreatedByOrganizer=false}), so a non-organizer task can never be proven
     * empty. Every read is wrapped; a missing API, a construction or reflection failure, a permission
     * failure or any mismatch answers {@code false} (fail closed).
     */
    private static boolean organizerEmptyProven(Object root,int[] childIds) {
        if(root==null||childIds==null) return false;
        try {
            int rootId=requiredInt(root,"taskId");
            if(!completeChildIds(childIds,rootId)) return false;
            Integer activityType=activityType(root);
            if(activityType==null||activityType.intValue()!=0) return false;
            Integer numActivities=intFieldOrNull(root,"numActivities");
            if(numActivities==null||numActivities.intValue()!=0) return false;
            if(!identityFree(root)) return false;
            Object token=field(root,"token");
            if(token==null) return false;
            Integer display=intFieldOrNull(root,"displayId");
            Integer user=intFieldOrNull(root,"userId");
            if(display==null||user==null) return false;
            List<?> children=organizerChildren(token);
            if(children==null||children.size()!=childIds.length) return false;
            Set<Integer> expected=new HashSet<Integer>();
            for(int id:childIds) expected.add(id);
            Set<Integer> seen=new HashSet<Integer>();
            for(Object child:children) {
                if(child==null) return false;
                Integer childId=intFieldOrNull(child,"taskId");
                if(childId==null||!expected.contains(childId)||!seen.add(childId)) return false;
                if(requiredInt(child,"parentTaskId")!=rootId) return false;
                if(requiredInt(child,"displayId")!=display.intValue()) return false;
                if(requiredInt(child,"userId")!=user.intValue()) return false;
                Integer childActivities=intFieldOrNull(child,"numActivities");
                if(childActivities==null||childActivities.intValue()!=0) return false;
                if(!identityFree(child)) return false;
            }
            return seen.equals(expected);
        } catch(Exception ex) {
            return false;
        }
    }
    /** A task field as an {@code Integer}, or {@code null} when the field is missing or not an int. */
    private static Integer intFieldOrNull(Object task,String name) {
        try {
            Object value=field(task,name);
            return value instanceof Integer ? (Integer)value : null;
        } catch(Exception ignored) { return null; }
    }
    private static int requiredInt(Object task,String name)throws Exception {
        Object value=field(task,name);
        if(!(value instanceof Integer)) throw new IllegalStateException("bad int "+name);
        return ((Integer)value).intValue();
    }
    /** Activity type via {@code getActivityType()} or the platform field aliases, or {@code null}. */
    private static Integer activityType(Object task) {
        try {
            Object value=task.getClass().getMethod("getActivityType").invoke(task);
            if(value instanceof Integer) return (Integer)value;
        } catch(Exception ignored) { }
        Integer direct=intFieldOrNull(task,"activityType");
        return direct!=null ? direct : intFieldOrNull(task,"mActivityType");
    }
    /** No package identity in the base intent or any component field; a malformed value throws. */
    private static boolean identityFree(Object task)throws Exception {
        Object rawIntent=field(task,"baseIntent");
        if(rawIntent!=null) {
            if(!(rawIntent instanceof Intent)) return false;
            Intent intent=(Intent)rawIntent;
            if(intent.getComponent()!=null||intent.getPackage()!=null) return false;
        }
        return componentPackage(field(task,"baseActivity"))==null
                && componentPackage(field(task,"topActivity"))==null
                && componentPackage(field(task,"realActivity"))==null
                && componentPackage(field(task,"origActivity"))==null;
    }
    /** Direct organizer children for a nonnull window container token, or {@code null} if unprovable. */
    private static List<?> organizerChildren(Object token)throws Exception {
        Class<?> organizerClass=Class.forName("android.window.TaskOrganizer");
        Method lookup=null;
        for(Method candidate:organizerClass.getMethods()) {
            Class<?>[] params=candidate.getParameterTypes();
            if("getChildTasks".equals(candidate.getName())&&params.length==2
                    && params[1]==int[].class&&params[0].isInstance(token)) { lookup=candidate; break; }
        }
        if(lookup==null) return null;
        // getChildTasks is an instance method: the standard TaskOrganizer exposes a public no-arg
        // constructor, so a throwaway instance is created to invoke it. Any failure (no such
        // constructor, permission denied, missing service) propagates and is treated as "unproven".
        Object organizer=organizerClass.getConstructor().newInstance();
        Object result=lookup.invoke(organizer,token,(Object)null);
        if(result==null) return null;
        if(!(result instanceof List)) throw new IllegalStateException("organizer child type");
        return (List<?>)result;
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
