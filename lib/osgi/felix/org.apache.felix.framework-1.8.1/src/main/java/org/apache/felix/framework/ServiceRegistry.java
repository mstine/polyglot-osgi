/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.framework;

import java.util.*;

import org.apache.felix.framework.util.FelixConstants;
import org.osgi.framework.*;
import org.osgi.framework.hooks.service.*;

public class ServiceRegistry
{
    private Logger m_logger = null;
    private long m_currentServiceId = 1L;
    // Maps bundle to an array of service registrations.
    private Map m_serviceRegsMap = new HashMap();
    // Maps registration to thread to keep track when a
    // registration is in use, which will cause other
    // threads to wait.
    private Map m_lockedRegsMap = new HashMap();
    // Maps bundle to an array of usage counts.
    private Map m_inUseMap = new HashMap();

    private ServiceListener m_serviceListener = null;

    private final Object m_eventHookLock = new Object();
    private Object[] m_eventHooks = new Object[0];
    private final Object m_findHookLock = new Object();
    private Object[] m_findHooks = new Object[0];
    private final Object m_listenerHookLock = new Object();
    private Object[] m_listenerHooks = new Object[0];

    public ServiceRegistry(Logger logger)
    {
        m_logger = logger;
    }

    public synchronized ServiceReference[] getRegisteredServices(Bundle bundle)
    {
        ServiceRegistration[] regs = (ServiceRegistration[]) m_serviceRegsMap.get(bundle);
        if (regs != null)
        {
            ServiceReference[] refs = new ServiceReference[regs.length];
            for (int i = 0; i < refs.length; i++)
            {
                refs[i] = regs[i].getReference();
            }
            return refs;
        }
        return null;
    }

    public ServiceRegistration registerService(
        Bundle bundle, String[] classNames, Object svcObj, Dictionary dict)
    {
        ServiceRegistration reg = null;

        synchronized (this)
        {
            // Keep track of registered hooks.
            addHooks(classNames, svcObj);

            // Create the service registration.
            reg = new ServiceRegistrationImpl(
                this, bundle, classNames, new Long(m_currentServiceId++), svcObj, dict);
            // Get the bundles current registered services.
            ServiceRegistration[] regs = (ServiceRegistration[]) m_serviceRegsMap.get(bundle);
            m_serviceRegsMap.put(bundle, addServiceRegistration(regs, reg));
        }
        fireServiceChanged(new ServiceEvent(ServiceEvent.REGISTERED, reg.getReference()));
        return reg;
    }

    public void unregisterService(Bundle bundle, ServiceRegistration reg)
    {
        // If this is a hook, it should be removed.
        removeHook(((ServiceRegistrationImpl) reg).getService());

        synchronized (this)
        {
            // Note that we don't lock the service registration here using
            // the m_lockedRegsMap because we want to allow bundles to get
            // the service during the unregistration process. However, since
            // we do remove the registration from the service registry, no
            // new bundles will be able to look up the service.

            // Now remove the registered service.
            ServiceRegistration[] regs = (ServiceRegistration[]) m_serviceRegsMap.get(bundle);
            m_serviceRegsMap.put(bundle, removeServiceRegistration(regs, reg));
        }

        // Fire the service event which gives all client bundles the
        // opportunity to unget their service object.
        fireServiceChanged(new ServiceEvent(ServiceEvent.UNREGISTERING, reg.getReference()));

        // Now forcibly unget the service object for all stubborn clients.
        synchronized (this)
        {
            Bundle[] clients = getUsingBundles(reg.getReference());
            for (int i = 0; (clients != null) && (i < clients.length); i++)
            {
                while (ungetService(clients[i], reg.getReference()))
                    ; // Keep removing until it is no longer possible
            }
            ((ServiceRegistrationImpl) reg).invalidate();
        }
    }

    /**
     * This method retrieves all services registrations for the specified
     * bundle and invokes <tt>ServiceRegistration.unregister()</tt> on each
     * one. This method is only called be the framework to clean up after
     * a stopped bundle.
     * @param bundle the bundle whose services should be unregistered.
    **/
    public void unregisterServices(Bundle bundle)
    {
        // Simply remove all service registrations for the bundle.
        ServiceRegistration[] regs = null;
        synchronized (this)
        {
            regs = (ServiceRegistration[]) m_serviceRegsMap.get(bundle);
        }

        // Note, there is no race condition here with respect to the
        // bundle registering more services, because its bundle context
        // has already been invalidated by this point, so it would not
        // be able to register more services.

        // Unregister each service.
        for (int i = 0; (regs != null) && (i < regs.length); i++)
        {
            if (((ServiceRegistrationImpl) regs[i]).isValid())
            {
                regs[i].unregister();
            }
        }

        // Now remove the bundle itself.
        synchronized (this)
        {
            m_serviceRegsMap.remove(bundle);
        }
    }

    public synchronized List getServiceReferences(String className, Filter filter)
    {
        // Create a filtered list of service references.
        List list = new ArrayList();

        // Iterator over all service registrations.
        for (Iterator i = m_serviceRegsMap.entrySet().iterator(); i.hasNext(); )
        {
            Map.Entry entry = (Map.Entry) i.next();
            ServiceRegistration[] regs = (ServiceRegistration[]) entry.getValue();

            for (int regIdx = 0;
                (regs != null) && (regIdx < regs.length);
                regIdx++)
            {
                // Determine if the registered services matches
                // the search criteria.
                boolean matched = false;

                // If className is null, then look at filter only.
                if ((className == null) &&
                    ((filter == null) || filter.match(regs[regIdx].getReference())))
                {
                    matched = true;
                }
                // If className is not null, then first match the
                // objectClass property before looking at the
                // filter.
                else if (className != null)
                {
                    String[] objectClass = (String[])
                        ((ServiceRegistrationImpl) regs[regIdx]).getProperty(FelixConstants.OBJECTCLASS);
                    for (int classIdx = 0;
                        classIdx < objectClass.length;
                        classIdx++)
                    {
                        if (objectClass[classIdx].equals(className) &&
                            ((filter == null) || filter.match(regs[regIdx].getReference())))
                        {
                            matched = true;
                            break;
                        }
                    }
                }

                // Add reference if it was a match.
                if (matched)
                {
                    list.add(regs[regIdx].getReference());
                }
            }
        }

        return list;
    }

    public synchronized ServiceReference[] getServicesInUse(Bundle bundle)
    {
        UsageCount[] usages = (UsageCount[]) m_inUseMap.get(bundle);
        if (usages != null)
        {
            ServiceReference[] refs = new ServiceReference[usages.length];
            for (int i = 0; i < refs.length; i++)
            {
                refs[i] = usages[i].m_ref;
            }
            return refs;
        }
        return null;
    }

    public Object getService(Bundle bundle, ServiceReference ref)
    {
        UsageCount usage = null;
        Object svcObj = null;

        // Get the service registration.
        ServiceRegistrationImpl reg = ((ServiceRegistrationImpl.ServiceReferenceImpl) ref).getServiceRegistration();

        synchronized (this)
        {
            // First make sure that no existing operation is currently
            // being performed by another thread on the service registration.
            for (Object o = m_lockedRegsMap.get(reg); (o != null); o = m_lockedRegsMap.get(reg))
            {
                // We don't allow cycles when we call out to the service factory.
                if (o.equals(Thread.currentThread()))
                {
                    throw new IllegalStateException(
                        "ServiceFactory.getService() resulted in a cycle.");
                }

                // Otherwise, wait for it to be freed.
                try
                {
                    wait();
                }
                catch (InterruptedException ex)
                {
                }
            }

            // Lock the service registration.
            m_lockedRegsMap.put(reg, Thread.currentThread());

            // Make sure the service registration is still valid.
            if (!reg.isValid())
            {
                // If the service registration is not valid, then this means
                // that the service provider unregistered the service. The spec
                // says that calls to get an unregistered service should always
                // return null (assumption: even if it is currently cached
                // by the bundle). So in this case, flush the service reference
                // from the cache and return null.
                flushUsageCount(bundle, ref);

                // It is not necessary to unget the service object from
                // the providing bundle, since the associated service is
                // unregistered and hence not in the list of registered services
                // of the providing bundle. This is precisely why the service
                // registration was not found above in the first place.
            }
            else
            {
                // Get the usage count, if any.
                usage = getUsageCount(bundle, ref);

                // If the service object is cached, then increase the usage
                // count and return the cached service object.
                if (usage != null)
                {
                    usage.m_count++;
                    svcObj = usage.m_svcObj;
                }
            }
        }

        // If we have a valid registration, but no usage count, that means we
        // don't have a cached service object, so we need to create one now
        // without holding the lock, since we will potentially call out to a
        // service factory.
        try
        {
            if (reg.isValid() && (usage == null))
            {
                // Get service object from service registration.
                svcObj = reg.getService(bundle);

                // Cache the service object.
                if (svcObj != null)
                {
                    synchronized (this)
                    {
                        // Unregistration can happen concurrently, so we need
                        // to double-check that we are still valid.
                        if (reg.isValid())
                        {
                            addUsageCount(bundle, ref, svcObj);
                        }
                        else
                        {
                            // The service must have been unregistered in the
                            // middle of our get operation, so null it.
                            svcObj = null;
                        }
                    }
                }
            }
        }
        finally
        {
            // Finally, unlock the service registration so that any threads
            // waiting for it can continue.
            synchronized (this)
            {
                m_lockedRegsMap.remove(reg);
                notifyAll();
            }
        }

        return svcObj;
    }

    public boolean ungetService(Bundle bundle, ServiceReference ref)
    {
        UsageCount usage = null;
        ServiceRegistrationImpl reg = ((ServiceRegistrationImpl.ServiceReferenceImpl) ref).getServiceRegistration();

        synchronized (this)
        {
            // First make sure that no existing operation is currently
            // being performed by another thread on the service registration.
            for (Object o = m_lockedRegsMap.get(reg); (o != null); o = m_lockedRegsMap.get(reg))
            {
                // We don't allow cycles when we call out to the service factory.
                if (o.equals(Thread.currentThread()))
                {
                    throw new IllegalStateException(
                        "ServiceFactory.ungetService() resulted in a cycle.");
                }

                // Otherwise, wait for it to be freed.
                try
                {
                    wait();
                }
                catch (InterruptedException ex)
                {
                }
            }

            // Get the usage count.
            usage = getUsageCount(bundle, ref);
            // If there is no cached services, then just return immediately.
            if (usage == null)
            {
                return false;
            }

            // Lock the service registration.
            m_lockedRegsMap.put(reg, Thread.currentThread());

            // Make sure the service registration is still valid.
            if (!reg.isValid())
            {
                // If the service registration is not valid, then this means
                // that the service provider unregistered the service, so just
                // flush the usage count and we are done.
                flushUsageCount(bundle, ref);
            }
            else if (reg.isValid())
            {
                // Decrement usage count.
                usage.m_count--;
            }

            // If the usage count has reached zero, the flush it.
            if (usage.m_count == 0)
            {
                flushUsageCount(bundle, ref);
            }
        }

        // If usage count goes to zero, then unget the service
        // from the registration; we do this outside the lock
        // since this might call out to the service factory.
        try
        {
            if (usage.m_count == 0)
            {
                // Remove reference from usages array.
                ((ServiceRegistrationImpl.ServiceReferenceImpl) ref)
                    .getServiceRegistration().ungetService(bundle, usage.m_svcObj);
                usage.m_svcObj = null;
            }

        }
        finally
        {
            // Finally, unlock the service registration so that any threads
            // waiting for it can continue.
            synchronized (this)
            {
                m_lockedRegsMap.remove(reg);
                notifyAll();
            }
        }

        return (usage.m_count > 0);
    }


    /**
     * This is a utility method to release all services being
     * used by the specified bundle.
     * @param bundle the bundle whose services are to be released.
    **/
    public void ungetServices(Bundle bundle)
    {
        UsageCount[] usages;
        synchronized (this)
        {
            usages = (UsageCount[]) m_inUseMap.get(bundle);
        }

        if (usages == null)
        {
            return;
        }

        // Note, there is no race condition here with respect to the
        // bundle using more services, because its bundle context
        // has already been invalidated by this point, so it would not
        // be able to look up more services.

        // Remove each service object from the
        // service cache.
        for (int i = 0; i < usages.length; i++)
        {
            // Keep ungetting until all usage count is zero.
            while (ungetService(bundle, usages[i].m_ref))
            {
                // Empty loop body.
            }
        }
    }

    public synchronized Bundle[] getUsingBundles(ServiceReference ref)
    {
        Bundle[] bundles = null;
        for (Iterator iter = m_inUseMap.entrySet().iterator(); iter.hasNext(); )
        {
            Map.Entry entry = (Map.Entry) iter.next();
            Bundle bundle = (Bundle) entry.getKey();
            UsageCount[] usages = (UsageCount[]) entry.getValue();
            for (int useIdx = 0; useIdx < usages.length; useIdx++)
            {
                if (usages[useIdx].m_ref.equals(ref))
                {
                    // Add the bundle to the array to be returned.
                    if (bundles == null)
                    {
                        bundles = new Bundle[] { bundle };
                    }
                    else
                    {
                        Bundle[] nbs = new Bundle[bundles.length + 1];
                        System.arraycopy(bundles, 0, nbs, 0, bundles.length);
                        nbs[bundles.length] = bundle;
                        bundles = nbs;
                    }
                }
            }
        }
        return bundles;
    }

    public void servicePropertiesModified(ServiceRegistration reg)
    {
        fireServiceChanged(new ServiceEvent(ServiceEvent.MODIFIED, reg.getReference()));
    }

    public Logger getLogger()
    {
        return m_logger;
    }

    private static ServiceRegistration[] addServiceRegistration(
        ServiceRegistration[] regs, ServiceRegistration reg)
    {
        if (regs == null)
        {
            regs = new ServiceRegistration[] { reg };
        }
        else
        {
            ServiceRegistration[] newRegs = new ServiceRegistration[regs.length + 1];
            System.arraycopy(regs, 0, newRegs, 0, regs.length);
            newRegs[regs.length] = reg;
            regs = newRegs;
        }
        return regs;
    }

    private static ServiceRegistration[] removeServiceRegistration(
        ServiceRegistration[] regs, ServiceRegistration reg)
    {
        for (int i = 0; (regs != null) && (i < regs.length); i++)
        {
            if (regs[i].equals(reg))
            {
                // If this is the only usage, then point to empty list.
                if ((regs.length - 1) == 0)
                {
                    regs = new ServiceRegistration[0];
                }
                // Otherwise, we need to do some array copying.
                else
                {
                    ServiceRegistration[] newRegs = new ServiceRegistration[regs.length - 1];
                    System.arraycopy(regs, 0, newRegs, 0, i);
                    if (i < newRegs.length)
                    {
                        System.arraycopy(
                            regs, i + 1, newRegs, i, newRegs.length - i);
                    }
                    regs = newRegs;
                }
            }
        }
        return regs;
    }

    public synchronized void addServiceListener(ServiceListener l)
    {
        m_serviceListener = ServiceListenerMulticaster.add(m_serviceListener, l);
    }

    public synchronized void removeServiceListener(ServiceListener l)
    {
        m_serviceListener = ServiceListenerMulticaster.remove(m_serviceListener, l);
    }

    protected void fireServiceChanged(ServiceEvent event)
    {
        // Grab a copy of the listener list.
        ServiceListener listener;
        synchronized (this)
        {
            listener = m_serviceListener;
        }
        // If not null, then dispatch event.
        if (listener != null)
        {
            listener.serviceChanged(event);
        }
    }

    private static class ServiceListenerMulticaster implements ServiceListener
    {
        protected ServiceListener m_a = null, m_b = null;

        protected ServiceListenerMulticaster(ServiceListener a, ServiceListener b)
        {
            m_a = a;
            m_b = b;
        }

        public void serviceChanged(ServiceEvent e)
        {
            m_a.serviceChanged(e);
            m_b.serviceChanged(e);
        }

        public static ServiceListener add(ServiceListener a, ServiceListener b)
        {
            if (a == null)
            {
                return b;
            }
            else if (b == null)
            {
                return a;
            }
            else
            {
                return new ServiceListenerMulticaster(a, b);
            }
        }

        public static ServiceListener remove(ServiceListener a, ServiceListener b)
        {
            if ((a == null) || (a == b))
            {
                return null;
            }
            else if (a instanceof ServiceListenerMulticaster)
            {
                return add(
                    remove(((ServiceListenerMulticaster) a).m_a, b),
                    remove(((ServiceListenerMulticaster) a).m_b, b));
            }
            else
            {
                return a;
            }
        }
    }

    /**
     * Utility method to retrieve the specified bundle's usage count for the
     * specified service reference.
     * @param bundle The bundle whose usage counts are being searched.
     * @param ref The service reference to find in the bundle's usage counts.
     * @return The associated usage count or null if not found.
    **/
    private UsageCount getUsageCount(Bundle bundle, ServiceReference ref)
    {
        UsageCount[] usages = (UsageCount[]) m_inUseMap.get(bundle);
        for (int i = 0; (usages != null) && (i < usages.length); i++)
        {
            if (usages[i].m_ref.equals(ref))
            {
                return usages[i];
            }
        }
        return null;
    }

    /**
     * Utility method to update the specified bundle's usage count array to
     * include the specified service. This method should only be called
     * to add a usage count for a previously unreferenced service. If the
     * service already has a usage count, then the existing usage count
     * counter simply needs to be incremented.
     * @param bundle The bundle acquiring the service.
     * @param ref The service reference of the acquired service.
     * @param svcObj The service object of the acquired service.
    **/
    private void addUsageCount(Bundle bundle, ServiceReference ref, Object svcObj)
    {
        UsageCount[] usages = (UsageCount[]) m_inUseMap.get(bundle);

        UsageCount usage = new UsageCount();
        usage.m_ref = ref;
        usage.m_svcObj = svcObj;
        usage.m_count++;

        if (usages == null)
        {
            usages = new UsageCount[] { usage };
        }
        else
        {
            UsageCount[] newUsages = new UsageCount[usages.length + 1];
            System.arraycopy(usages, 0, newUsages, 0, usages.length);
            newUsages[usages.length] = usage;
            usages = newUsages;
        }

        m_inUseMap.put(bundle, usages);
    }

    /**
     * Utility method to flush the specified bundle's usage count for the
     * specified service reference. This should be called to completely
     * remove the associated usage count object for the specified service
     * reference. If the goal is to simply decrement the usage, then get
     * the usage count and decrement its counter. This method will also
     * remove the specified bundle from the "in use" map if it has no more
     * usage counts after removing the usage count for the specified service
     * reference.
     * @param bundle The bundle whose usage count should be removed.
     * @param ref The service reference whose usage count should be removed.
    **/
    private void flushUsageCount(Bundle bundle, ServiceReference ref)
    {
        UsageCount[] usages = (UsageCount[]) m_inUseMap.get(bundle);
        for (int i = 0; (usages != null) && (i < usages.length); i++)
        {
            if (usages[i].m_ref.equals(ref))
            {
                // If this is the only usage, then point to empty list.
                if ((usages.length - 1) == 0)
                {
                    usages = null;
                }
                // Otherwise, we need to do some array copying.
                else
                {
                    UsageCount[] newUsages = new UsageCount[usages.length - 1];
                    System.arraycopy(usages, 0, newUsages, 0, i);
                    if (i < newUsages.length)
                    {
                        System.arraycopy(
                            usages, i + 1, newUsages, i, newUsages.length - i);
                    }
                    usages = newUsages;
                }
            }
        }

        if (usages != null)
        {
            m_inUseMap.put(bundle, usages);
        }
        else
        {
            m_inUseMap.remove(bundle);
        }
    }

    private void addHooks(String[] classNames, Object svcObj)
    {
        if (isHook(classNames, EventHook.class, svcObj))
        {
            synchronized (m_eventHookLock)
            {
                m_eventHooks = addToArray(m_eventHooks, svcObj);
            }
        }

        if (isHook(classNames, FindHook.class, svcObj))
        {
            synchronized (m_findHookLock)
            {
                m_findHooks = addToArray(m_findHooks, svcObj);
            }
        }

        if (isHook(classNames, ListenerHook.class, svcObj))
        {
            synchronized (m_listenerHookLock)
            {
                m_listenerHooks = addToArray(m_listenerHooks, svcObj);
            }
        }
    }

    private static Object[] addToArray(Object[] src, Object svcObj)
    {
        Object[] dst = new Object[src.length + 1];
        System.arraycopy(src, 0, dst, 0, src.length);
        dst[src.length] = svcObj;
        return dst;
    }

    boolean isHook(String[] classNames, Class hookClass, Object svcObj)
    {
        if (hookClass.isAssignableFrom(svcObj.getClass()))
        {
            String hookName = hookClass.getName();
            for (int i = 0; i < classNames.length; i++)
            {
                if (classNames[i].equals(hookName))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private void removeHook(Object svcObj)
    {
        if (svcObj instanceof EventHook)
        {
            synchronized (m_eventHookLock)
            {
                m_eventHooks = removeFromArray(m_eventHooks, svcObj);
            }
        }

        if (svcObj instanceof FindHook)
        {
            synchronized (m_findHookLock)
            {
                m_findHooks = removeFromArray(m_findHooks, svcObj);
            }
        }

        if (svcObj instanceof ListenerHook)
        {
            synchronized (m_listenerHookLock)
            {
                m_listenerHooks = removeFromArray(m_listenerHooks, svcObj);
            }
        }
    }

    private static Object[] removeFromArray(Object[] src, Object svcObj)
    {
        Object[] dst = src;

        int idx = -1;
        for (int i = 0; i < src.length; i++)
        {
            if (src[i].equals(svcObj))
            {
                idx = i;
                break;
            }
        }

        if (idx >= 0)
        {
            if (src.length == 1)
            {
                dst = new Object[0];
            }
            else
            {
                dst = new Object[src.length - 1];
                System.arraycopy(dst, 0, src, 0, idx);
                if (idx < dst.length)
                {
                    System.arraycopy(
                        src, idx + 1, dst, idx, dst.length - idx);
                }
            }
        }

        return dst;
    }

    public List getEventHooks()
    {
        synchronized (m_eventHooks)
        {
            return Collections.unmodifiableList(Arrays.asList(m_eventHooks));
        }
    }

    List getFindHooks()
    {
        synchronized (m_findHooks)
        {
            return Collections.unmodifiableList(Arrays.asList(m_findHooks));
        }
    }

    List getListenerHooks()
    {
        synchronized (m_listenerHooks)
        {
            return Collections.unmodifiableList(Arrays.asList(m_listenerHooks));
        }
    }

    private static class UsageCount
    {
        public int m_count = 0;
        public ServiceReference m_ref = null;
        public Object m_svcObj = null;
    }
}