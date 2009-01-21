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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.*;

import org.apache.felix.framework.cache.BundleArchive;
import org.apache.felix.framework.searchpolicy.ModuleImpl;
import org.apache.felix.framework.searchpolicy.URLPolicyImpl;
import org.apache.felix.framework.util.manifestparser.ManifestParser;
import org.apache.felix.framework.util.manifestparser.R4Library;
import org.apache.felix.moduleloader.IModule;
import org.osgi.framework.*;

class BundleImpl implements Bundle
{
    private final Felix m_felix;

    private final BundleArchive m_archive;
    private IModule[] m_modules = new IModule[0];
    private int m_state;
    private BundleActivator m_activator = null;
    private BundleContext m_context = null;
    private final Map m_cachedHeaders = new HashMap();
    private long m_cachedHeadersTimestamp;

    // Indicates whether the bundle has been updated/uninstalled
    // and is waiting to be refreshed.
    private boolean m_removalPending = false;
    // Indicates whether the bundle is stale, meaning that it has
    // been refreshed and completely removed from the framework.
    private boolean m_stale = false;

    // Indicates whether the bundle is an extension, meaning that it is
    // installed as an extension bundle to the framework (i.e., can not be
    // removed or updated until a framework restart.
    private boolean m_extension = false;

    // Used for bundle locking.
    private int m_lockCount = 0;
    private Thread m_lockThread = null;

    BundleImpl(Felix felix, BundleArchive archive) throws Exception
    {
        m_felix = felix;
        m_archive = archive;
        m_state = Bundle.INSTALLED;
        m_stale = false;
        m_activator = null;
        m_context = null;

        // TODO: REFACTOR - Null check is a hack due to system bundle.
        if (m_archive != null)
        {
            addModule(createModule());
        }
    }

    // TODO: REFACTOR - We need this method so the system bundle can override it.
    Felix getFramework()
    {
        return m_felix;
    }

    synchronized void reset() throws Exception
    {
        m_modules = new IModule[0];
        addModule(createModule());
        m_state = Bundle.INSTALLED;
        m_stale = false;
        m_cachedHeaders.clear();
        m_cachedHeadersTimestamp = 0;
        m_removalPending = false;
    }

    synchronized BundleActivator getActivator()
    {
        return m_activator;
    }

    synchronized void setActivator(BundleActivator activator)
    {
        m_activator = activator;
    }

    public synchronized BundleContext getBundleContext()
    {
// TODO: SECURITY - We need a security check here.
        return m_context;
    }

    synchronized void setBundleContext(BundleContext context)
    {
        m_context = context;
    }

    public long getBundleId()
    {
        try
        {
            return m_archive.getId();
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(
                Logger.LOG_ERROR,
                "Error getting the identifier from bundle archive.",
                ex);
            return -1;
        }
    }

    public URL getEntry(String name)
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            try
            {
                ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                    AdminPermission.RESOURCE));
            }
            catch (Exception e)
            {
                return null; // No permission
            }
        }

        return getFramework().getBundleEntry(this, name);
    }

    public Enumeration getEntryPaths(String path)
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            try
            {
                ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                    AdminPermission.RESOURCE));
            }
            catch (Exception e)
            {
                return null; // No permission
            }
        }

        return getFramework().getBundleEntryPaths(this, path);
    }

    public Enumeration findEntries(String path, String filePattern, boolean recurse)
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            try
            {
                ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                    AdminPermission.RESOURCE));
            }
            catch (Exception e)
            {
                return null; // No permission
            }
        }

        return getFramework().findBundleEntries(this, path, filePattern, recurse);
    }

    public Dictionary getHeaders()
    {
        return getHeaders(Locale.getDefault().toString());
    }

    public Dictionary getHeaders(String locale)
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                AdminPermission.METADATA));
        }

        if (locale == null)
        {
            locale = Locale.getDefault().toString();
        }

        return getFramework().getBundleHeaders(this, locale);
    }

    Map getCurrentLocalizedHeader(String locale)
    {
        synchronized (m_cachedHeaders)
        {
            // If the bundle has been updated, clear the cached headers
            if (getLastModified() > m_cachedHeadersTimestamp)
            {
                m_cachedHeaders.clear();
            }
            else
            {
                // Check if headers for this locale have already been resolved
                if (m_cachedHeaders.containsKey(locale))
                {
                    return (Map) m_cachedHeaders.get(locale);
                }
            }
        }

        Map rawHeaders = getCurrentModule().getHeaders();
        Map headers = new HashMap(rawHeaders.size());
        headers.putAll(rawHeaders);

        // Check to see if we actually need to localize anything
        boolean needsLocalization = false;
        for (Iterator it = headers.values().iterator(); it.hasNext(); )
        {
            if (((String) it.next()).startsWith("%"))
            {
                needsLocalization = true;
                break;
            }
        }

        if (!needsLocalization)
        {
            // If localization is not needed, just cache the headers and return them as-is
            // Not sure if this is useful
            updateHeaderCache(locale, headers);
            return headers;
        }

        // Do localization here and return the localized headers
        String basename = (String) headers.get(Constants.BUNDLE_LOCALIZATION);
        if (basename == null)
        {
            basename = Constants.BUNDLE_LOCALIZATION_DEFAULT_BASENAME;
        }

        // Create ordered list of files to load properties from
        List resourceList = createResourceList(basename, locale);

        // Create a merged props file with all available props for this locale
        Properties mergedProperties = new Properties();
        for (Iterator it = resourceList.iterator(); it.hasNext(); )
        {
            URL temp = this.getCurrentModule().getResourceFromModule(it.next() + ".properties");
            if (temp == null)
            {
                continue;
            }
            try
            {
                mergedProperties.load(temp.openConnection().getInputStream());
            }
            catch (IOException ex)
            {
                // File doesn't exist, just continue loop
            }
        }

        // Resolve all localized header entries
        for (Iterator it = headers.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry entry = (Map.Entry) it.next();
            String value = (String) entry.getValue();
            if (value.startsWith("%"))
            {
                String newvalue;
                String key = value.substring(value.indexOf("%") + 1);
                newvalue = mergedProperties.getProperty(key);
                if (newvalue==null)
                {
                    newvalue = key;
                }
                entry.setValue(newvalue);
            }
        }

        updateHeaderCache(locale, headers);
        return headers;
    }

    private void updateHeaderCache(String locale, Map localizedHeaders)
    {
        synchronized (m_cachedHeaders)
        {
            m_cachedHeaders.put(locale, localizedHeaders);
            m_cachedHeadersTimestamp = System.currentTimeMillis();
        }
    }

    private List createResourceList(String basename, String locale)
    {
        List result = new ArrayList(4);

        StringTokenizer tokens;
        StringBuffer tempLocale = new StringBuffer(basename);

        result.add(tempLocale.toString());

        if (locale.length() > 0)
        {
            tokens = new StringTokenizer(locale, "_");
            while (tokens.hasMoreTokens())
            {
                tempLocale.append("_").append(tokens.nextToken());
                result.add(tempLocale.toString());
            }
        }
        return result;
    }

    public long getLastModified()
    {
        try
        {
            return m_archive.getLastModified();
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(
                Logger.LOG_ERROR,
                "Error reading last modification time from bundle archive.",
                ex);
            return 0;
        }
    }

    void setLastModified(long l)
    {
        try
        {
            m_archive.setLastModified(l);
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(
                Logger.LOG_ERROR,
                "Error writing last modification time to bundle archive.",
                ex);
        }
    }

    public String getLocation()
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                AdminPermission.METADATA));
        }
        return _getLocation();
    }

    String _getLocation()
    {
        try
        {
            return m_archive.getLocation();
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(
                Logger.LOG_ERROR,
                "Error getting location from bundle archive.",
                ex);
            return null;
        }
    }

    /**
     * Returns a URL to a named resource in the bundle.
     *
     * @return a URL to named resource, or null if not found.
    **/
    public URL getResource(String name)
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            try
            {
                ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                    AdminPermission.RESOURCE));
            }
            catch (Exception e)
            {
                return null; // No permission
            }
        }

        return getFramework().getBundleResource(this, name);
    }

    public Enumeration getResources(String name) throws IOException
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            try
            {
                ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                    AdminPermission.RESOURCE));
            }
            catch (Exception e)
            {
                return null; // No permission
            }
        }

        return getFramework().getBundleResources(this, name);
    }

    /**
     * Returns an array of service references corresponding to
     * the bundle's registered services.
     *
     * @return an array of service references or null.
    **/
    public ServiceReference[] getRegisteredServices()
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            ServiceReference[] refs = getFramework().getBundleRegisteredServices(this);

            if (refs == null)
            {
                return refs;
            }

            List result = new ArrayList();

            for (int i = 0; i < refs.length; i++)
            {
                String[] objectClass = (String[]) refs[i].getProperty(
                    Constants.OBJECTCLASS);

                if (objectClass == null)
                {
                    continue;
                }

                for (int j = 0; j < objectClass.length; j++)
                {
                    try
                    {
                        ((SecurityManager) sm).checkPermission(new ServicePermission(
                            objectClass[j], ServicePermission.GET));

                        result.add(refs[i]);

                        break;
                    }
                    catch (Exception ex)
                    {
                        // Silently ignore.
                    }
                }
            }

            if (result.isEmpty())
            {
                return null;
            }

            return (ServiceReference[]) result.toArray(new ServiceReference[result.size()]);
        }
        else
        {
            return getFramework().getBundleRegisteredServices(this);
        }
    }

    public ServiceReference[] getServicesInUse()
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            ServiceReference[] refs = getFramework().getBundleServicesInUse(this);

            if (refs == null)
            {
                return refs;
            }

            List result = new ArrayList();

            for (int i = 0; i < refs.length; i++)
            {
                String[] objectClass = (String[]) refs[i].getProperty(
                    Constants.OBJECTCLASS);

                if (objectClass == null)
                {
                    continue;
                }

                for (int j = 0; j < objectClass.length; j++)
                {
                    try
                    {
                        ((SecurityManager) sm).checkPermission(new ServicePermission(
                            objectClass[j], ServicePermission.GET));

                        result.add(refs[i]);

                        break;
                    }
                    catch (Exception ex)
                    {
                        // Silently ignore.
                    }
                }
            }

            if (result.isEmpty())
            {
                return null;
            }

            return (ServiceReference[]) result.toArray(new ServiceReference[result.size()]);
        }

        return getFramework().getBundleServicesInUse(this);
    }

    public synchronized int getState()
    {
        return m_state;
    }

    synchronized void setState(int i)
    {
        m_state = i;
    }

    int getPersistentState()
    {
        try
        {
            return m_archive.getPersistentState();
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(
                Logger.LOG_ERROR,
                "Error reading persistent state from bundle archive.",
                ex);
            return Bundle.INSTALLED;
        }
    }

    void setPersistentStateInactive()
    {
        try
        {
            m_archive.setPersistentState(Bundle.INSTALLED);
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(Logger.LOG_ERROR,
                "Error writing persistent state to bundle archive.",
                ex);
        }
    }

    void setPersistentStateActive()
    {
        try
        {
            m_archive.setPersistentState(Bundle.ACTIVE);
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(
                Logger.LOG_ERROR,
                "Error writing persistent state to bundle archive.",
                ex);
        }
    }

    void setPersistentStateUninstalled()
    {
        try
        {
            m_archive.setPersistentState(Bundle.UNINSTALLED);
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(
                Logger.LOG_ERROR,
                "Error writing persistent state to bundle archive.",
                ex);
        }
    }

    int getStartLevel(int defaultLevel)
    {
        try
        {
            return m_archive.getStartLevel();
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(
                Logger.LOG_ERROR,
                "Error reading start level from bundle archive.",
                ex);
            return defaultLevel;
        }
    }

    void setStartLevel(int i)
    {
        try
        {
            m_archive.setStartLevel(i);
        }
        catch (Exception ex)
        {
            getFramework().getLogger().log(
                Logger.LOG_ERROR,
                "Error writing start level to bundle archive.",
                ex);
        }
    }

    synchronized boolean isStale()
    {
        return m_stale;
    }

    synchronized void setStale()
    {
        m_stale = true;
    }

    synchronized boolean isExtension()
    {
        return m_extension;
    }

    synchronized void setExtension(boolean extension)
    {
        m_extension = extension;
    }

    public String getSymbolicName()
    {
        return getCurrentModule().getSymbolicName();
    }

    public boolean hasPermission(Object obj)
    {
        return getFramework().bundleHasPermission(this, obj);
    }

    Object getSignerMatcher()
    {
        return getFramework().getSignerMatcher(this);
    }

    public Class loadClass(String name) throws ClassNotFoundException
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            try
            {
                ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                    AdminPermission.CLASS));
            }
            catch (Exception ex)
            {
                throw new ClassNotFoundException("No permission.", ex);
            }
        }

        return getFramework().loadBundleClass(this, name);
    }

    public void start() throws BundleException
    {
        start(0);
    }

    public void start(int options) throws BundleException
    {
        if ((options & Bundle.START_ACTIVATION_POLICY) > 0)
        {
            throw new UnsupportedOperationException(
                "The activation policy feature has not yet been implemented.");
        }

        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                AdminPermission.EXECUTE));
        }

        getFramework().startBundle(this, ((options & Bundle.START_TRANSIENT) == 0));
    }

    public void update() throws BundleException
    {
        update(null);
    }

    public void update(InputStream is) throws BundleException
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                AdminPermission.LIFECYCLE));
        }

        getFramework().updateBundle(this, is);
    }

    public void stop() throws BundleException
    {
        stop(0);
    }

    public void stop(int options) throws BundleException
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                AdminPermission.EXECUTE));
        }

        getFramework().stopBundle(this, ((options & Bundle.STOP_TRANSIENT) == 0));
    }

    public void uninstall() throws BundleException
    {
        Object sm = System.getSecurityManager();

        if (sm != null)
        {
            ((SecurityManager) sm).checkPermission(new AdminPermission(this,
                AdminPermission.LIFECYCLE));
        }

        getFramework().uninstallBundle(this);
    }

    public String toString()
    {
        String sym = getCurrentModule().getSymbolicName();
        if (sym != null)
        {
            return sym + " [" + getBundleId() +"]";
        }
        return "[" + getBundleId() +"]";
    }

    synchronized boolean isRemovalPending()
    {
        return m_removalPending;
    }

    synchronized void setRemovalPending(boolean removalPending)
    {
        m_removalPending = removalPending;
    }

    //
    // Module management.
    //

    /**
     * Returns an array of all modules associated with the bundle represented by
     * this <tt>BundleInfo</tt> object. A module in the array corresponds to a
     * revision of the bundle's JAR file and is ordered from oldest to newest.
     * Multiple revisions of a bundle JAR file might exist if a bundle is
     * updated, without refreshing the framework. In this case, exports from
     * the prior revisions of the bundle JAR file are still offered; the
     * current revision will be bound to packages from the prior revision,
     * unless the packages were not offered by the prior revision. There is
     * no limit on the potential number of bundle JAR file revisions.
     * @return array of modules corresponding to the bundle JAR file revisions.
    **/
    synchronized IModule[] getModules()
    {
        return m_modules;
    }

    /**
     * Determines if the specified module is associated with this bundle.
     * @param module the module to determine if it is associate with this bundle.
     * @return <tt>true</tt> if the specified module is in the array of modules
     *         associated with this bundle, <tt>false</tt> otherwise.
    **/
    synchronized boolean hasModule(IModule module)
    {
        for (int i = 0; i < m_modules.length; i++)
        {
            if (m_modules[i] == module)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the newest module, which corresponds to the last module
     * in the module array.
     * @return the newest module.
    **/
    synchronized IModule getCurrentModule()
    {
        return m_modules[m_modules.length - 1];
    }

    synchronized boolean isUsed()
    {
        boolean used = false;
        for (int i = 0; !used && (i < m_modules.length); i++)
        {
            IModule[] dependents = ((ModuleImpl) m_modules[i]).getDependents();
            for (int j = 0; (dependents != null) && (j < dependents.length) && !used; j++)
            {
                if (dependents[j] != m_modules[i])
                {
                    used = true;
                }
            }
        }
        return used;
    }

    synchronized void revise(String location, InputStream is) throws Exception
    {
        // This operation will increase the revision count for the bundle.
        m_archive.revise(location, is);
        addModule(createModule());
    }

    synchronized boolean rollbackRevise() throws Exception
    {
        return m_archive.rollbackRevise();
    }

    // TODO: REFACTOR - This module is only visible for the system bundle.
    synchronized void addModule(IModule module)
    {
        ((ModuleImpl) module).setBundle(this);

        IModule[] dest = new IModule[m_modules.length + 1];
        System.arraycopy(m_modules, 0, dest, 0, m_modules.length);
        dest[m_modules.length] = module;
        m_modules = dest;
    }

    private synchronized IModule createModule() throws Exception
    {
        // Get and parse the manifest from the most recent revision to
        // create an associated module for it.
        Map headerMap = m_archive.getRevision(
            m_archive.getRevisionCount() - 1).getManifestHeader();
        ManifestParser mp = new ManifestParser(
            getFramework().getLogger(), getFramework().getConfig(), headerMap);

        // Verify that the bundle symbolic name and version is unique.
        if (mp.getManifestVersion().equals("2"))
        {
            Version bundleVersion = mp.getBundleVersion();
            bundleVersion = (bundleVersion == null) ? Version.emptyVersion : bundleVersion;
            String symName = mp.getSymbolicName();

            Bundle[] bundles = getFramework().getBundles();
            for (int i = 0; (bundles != null) && (i < bundles.length); i++)
            {
                long id = ((BundleImpl) bundles[i]).getBundleId();
                if (id != getBundleId())
                {
                    String sym = bundles[i].getSymbolicName();
                    Version ver = Version.parseVersion((String) ((BundleImpl) bundles[i])
                        .getCurrentModule().getHeaders().get(Constants.BUNDLE_VERSION));
                    if (symName.equals(sym) && bundleVersion.equals(ver))
                    {
                        throw new BundleException("Bundle symbolic name and version are not unique: " + sym + ':' + ver);
                    }
                }
            }
        }

        // Now that we have parsed and verified the module metadata, we
        // can actually create the module. Note, if this is an extension
        // bundle it's exports are removed, aince they will be added to
        // the system bundle directly later on.
        final int revision = m_archive.getRevisionCount() - 1;
        IModule module = new ModuleImpl(
            getFramework().getLogger(),
            getFramework().getConfig(),
            getFramework().getResolver(),
            Long.toString(getBundleId()) + "." + Integer.toString(revision),
            m_archive.getRevision(revision).getContent(),
            headerMap,
            (ExtensionManager.isExtensionBundle(headerMap)) ? null : mp.getCapabilities(),
            mp.getRequirements(),
            mp.getDynamicRequirements(),
            mp.getLibraries());

        // Set the content loader's URL policy.
        module.setURLPolicy(
// TODO: REFACTOR - SUCKS NEEDING URL POLICY PER MODULE.
            new URLPolicyImpl(
                getFramework().getLogger(),
                getFramework().getBundleStreamHandler(),
                module));

        // Verify that all native libraries exist in advance; this will
        // throw an exception if the native library does not exist.
        // TODO: CACHE - It would be nice if this check could be done
        //               some place else in the module, perhaps.
        R4Library[] libs = module.getNativeLibraries();
        for (int i = 0; (libs != null) && (i < libs.length); i++)
        {
            String entryName = libs[i].getEntryName();
            if (entryName != null)
            {
                if (module.getContent().getEntryAsNativeLibrary(entryName) == null)
                {
                    throw new BundleException("Native library does not exist: " + entryName);
// TODO: REFACTOR - We have a memory leak here since we added a module above
//                  and then don't remove it in case of an error; this may also
//                  be a general issue for installing/updating bundles, so check.
//                  This will likely go away when we refactor out the module
//                  factory, but we will track it under FELIX-835 until then.
                }
            }
        }

        return module;
    }

    void setProtectionDomain(ProtectionDomain pd)
    {
        getCurrentModule().setSecurityContext(pd);
    }

    synchronized ProtectionDomain getProtectionDomain()
    {
        ProtectionDomain pd = null;

        for (int i = m_modules.length - 1; (i >= 0) && (pd == null); i--)
        {
            pd = (ProtectionDomain) m_modules[i].getSecurityContext();
        }

        return pd;
    }

    //
    // Locking related methods.
    //

    synchronized boolean isLockable()
    {
        return (m_lockCount == 0) || (m_lockThread == Thread.currentThread());
    }

    synchronized void lock()
    {
        if ((m_lockCount > 0) && (m_lockThread != Thread.currentThread()))
        {
            throw new IllegalStateException("Bundle is locked by another thread.");
        }
        m_lockCount++;
        m_lockThread = Thread.currentThread();
    }

    synchronized void unlock()
    {
        if (m_lockCount == 0)
        {
            throw new IllegalStateException("Bundle is not locked.");
        }
        if ((m_lockCount > 0) && (m_lockThread != Thread.currentThread()))
        {
            throw new IllegalStateException("Bundle is locked by another thread.");
        }
        m_lockCount--;
        if (m_lockCount == 0)
        {
            m_lockThread = null;
        }
    }

    synchronized void syncLock(BundleImpl impl)
    {
        m_lockCount = impl.m_lockCount;
        m_lockThread = impl.m_lockThread;
    }
}