package tod.utils;

import java.io.Serializable;
import java.util.List;
import java.util.regex.Pattern;

import tod.core.database.browser.IEventPredicate;
import tod.core.database.event.IBehaviorCallEvent;
import tod.core.database.event.IBehaviorExitEvent;
import tod.core.database.event.ILogEvent;
import tod.core.database.structure.BehaviorKind;
import tod.core.database.structure.IBehaviorInfo;
import tod.core.database.structure.IClassInfo;
import tod.core.database.structure.ITypeInfo;

public class BehaviorEventPredicate implements IEventPredicate, Serializable {

    private static final long serialVersionUID = -6165331537330168406L;

    public BehaviorEventPredicate(IBehaviorInfo behaviorInfo, boolean matchConstructors, boolean matchExits,
            List<Pattern> classNameFilters, List<ITypeInfo> classFilters) {
        this.matchConstructors = matchConstructors;
        this.matchExits = matchExits;
        this.classNameFilters = classNameFilters;
        this.classFilters = classFilters;
    }

    protected final boolean matchConstructors;
    protected final boolean matchExits;
    private final List<Pattern> classNameFilters;
    private final List<ITypeInfo> classFilters;
    
    public boolean match(ILogEvent aEvent) {
        IBehaviorInfo behaviorInfo;
        if (matchExits) {
            if (aEvent instanceof IBehaviorExitEvent) {
                behaviorInfo = ((IBehaviorExitEvent)aEvent).getOperationBehavior();
            } else {
                return false;
            }
        } else {
            if (aEvent instanceof IBehaviorCallEvent) {
                behaviorInfo = ((IBehaviorCallEvent)aEvent).getExecutedBehavior();
                if (behaviorInfo == null) {
                    // Can happen for calls into non-instrumented code
                    return false;
                }
            } else {
                return false;
            }
        }
        
        BehaviorKind behaviourKind = behaviorInfo.getBehaviourKind();
        if (matchConstructors) {
            if (behaviourKind != BehaviorKind.CONSTRUCTOR) {
                return false;
            }
        } else {
            if (behaviourKind != BehaviorKind.METHOD && behaviourKind != BehaviorKind.STATIC_METHOD) {
                return false;
            }
        }
        
        IClassInfo declaringType = behaviorInfo.getDeclaringType();
        String className = declaringType.getName();
        for (Pattern classNameFilter : classNameFilters) {
            if (!classNameFilter.matcher(className).matches()) {
                return false;
            }
        }
        for (ITypeInfo classFilter : classFilters) {
            if (!classFilter.equals(declaringType)) {
                return false;
            }
        }
        return true;
    }
}
