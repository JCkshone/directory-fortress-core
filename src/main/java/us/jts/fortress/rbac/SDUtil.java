/*
 * Copyright (c) 2009-2013, JoshuaTree. All Rights Reserved.
 */

package us.jts.fortress.rbac;

import us.jts.fortress.GlobalErrIds;
import us.jts.fortress.GlobalIds;
import us.jts.fortress.ReviewMgrFactory;
import us.jts.fortress.SecurityException;
import us.jts.fortress.ReviewMgr;
import us.jts.fortress.cfg.Config;
import us.jts.fortress.util.attr.VUtil;
import us.jts.fortress.util.cache.Cache;
import us.jts.fortress.util.cache.CacheMgr;
import us.jts.fortress.util.cache.DsdCacheEntry;
import us.jts.fortress.util.time.Constraint;
import net.sf.ehcache.search.Attribute;
import net.sf.ehcache.search.Query;
import net.sf.ehcache.search.Result;
import net.sf.ehcache.search.Results;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This utilty provides functionality necessary for SSD and DSD processing and cannot be called by components outside fortress.
 * This class also contains utility functions for maintaining the SSD and DSD cache.
 * <p/>
 * This class is thread safe.
 *
 * @author Shawn McKinney
 * @created September 3, 2010
 */
final class SDUtil
{
    private static final String CLS_NM = SDUtil.class.getName();
    private static Cache m_dsdCache;
    private static final String FORTRESS_DSDS = "fortress.dsd";
    private static Cache m_ssdCache;
    private static final String FORTRESS_SSDS = "fortress.ssd";
    private static final SdP sp = new SdP();
    private static final String IS_DSD_CACHE_DISABLED_PARM = "enable.dsd.cache";
    private static final String MEMBER = "member";
    private static final String DSD_NAME = "name";
    private static final String EMPTY_ELEMENT = "empty";
    private static final String CONTEXT_ID = "contextId";

    static
    {
        // Get a reference to the CacheManager Singleton object:
        CacheMgr cacheMgr = CacheMgr.getInstance();
        // This cache contains a wrapper entry for DSD and is searchable by both DSD and Role name:
        m_dsdCache = cacheMgr.getCache(FORTRESS_DSDS);
        // This cache is not searchable and contains Lists of SSD objects by Role:
        m_ssdCache = cacheMgr.getCache(FORTRESS_SSDS);
    }

    /**
     * This method is called by AdminMgr.assignUser and is used to validate Static Separation of Duty
     * constraints when assigning a role to user.
     *
     * @param uRole
     * @throws us.jts.fortress.SecurityException
     *
     */
    static void validateSSD(UserRole uRole)
        throws SecurityException
    {
        validateSSD(new User(uRole.getUserId()), new Role(uRole.getName()));
    }

    /**
     * This method is called by AdminMgr.assignUser and is used to validate Static Separation of Duty
     * constraints when assigning a role to user.
     *
     * @param user
     * @param role
     * @throws us.jts.fortress.SecurityException
     *
     */
    static void validateSSD(User user, Role role)
        throws SecurityException
    {
        int matchCount;
        // get all authorized roles for user
        ReviewMgr rMgr = ReviewMgrFactory.createInstance(user.getContextId());
        Set<String> rls = rMgr.authorizedRoles(user);
        // Need to proceed?
        if (!VUtil.isNotNullOrEmpty(rls))
        {
            return;
        }

        // get all SSD sets that contain the new role
        List<SDSet> ssdSets = getSsdCache(role.getName(), user.getContextId());
        for (SDSet ssd : ssdSets)
        {
            matchCount = 0;
            Set<String> map = ssd.getMembers();
            // iterate over every authorized role for user:
            for (String authRole : rls)
            {
                // is there a match found between authorized role and SSD set's members?
                if (map.contains(authRole))
                {
                    matchCount++;
                    // does the match count exceed the cardinality allowed for this particular SSD set?
                    if (matchCount >= ssd.getCardinality() - 1)
                    {
                        String error = CLS_NM + ".validateSSD new role [" + role.getName() + "] validates SSD Set Name:" + ssd.getName() + " Cardinality:" + ssd.getCardinality() + ", Count:" + matchCount;
                        throw new SecurityException(GlobalErrIds.SSD_VALIDATION_FAILED, error);
                    }
                }
            }
        }
    }

    /**
     * This method is called by AccessMgr.addActiveRole and is used to validate Dynamic Separation of Duty
     * constraints when activating a role one at a time.  For activation of multiple roles simultaneously use
     * the DSD.validate API which is used during createSession sequence.
     *
     * @param session
     * @param role
     * @throws us.jts.fortress.SecurityException
     *
     */
    static void validateDSD(Session session, Constraint role)
        throws SecurityException
    {
        // get all activated roles from user's session:
        List<UserRole> rls = session.getRoles();
        if (!VUtil.isNotNullOrEmpty(rls))
        {
            // An empty list of roles was passed in the session variable.
            // No need to continue.
            return;
        }

        // get all DSD sets that contain the target role
        Set<SDSet> dsdSets = getDsdCache(role.getName(), session.getContextId());
        for (SDSet dsd : dsdSets)
        {
            // Keeps the number of matched roles to a particular DSD set.
            int matchCount = 0;

            // Contains the list of roles assigned to a particular DSD set.
            Set<String> map = dsd.getMembers();

            // iterate over every role active in session for match wth DSD members:
            for (UserRole actRole : rls)
            {
                // is there a match found between active role in session and DSD set members?
                if (map.contains(actRole.getName()))
                {
                    // Yes, we found a match, increment the count.
                    matchCount++;

                    // Does the match count exceed the cardinality allowed for this particular DSD set?
                    if (matchCount >= dsd.getCardinality() - 1)
                    {
                        // Yes, the target role violates DSD cardinality rule.
                        String error = CLS_NM + ".validateDSD failed for role [" + role.getName() + "] DSD Set Name:" + dsd.getName() + " Cardinality:" + dsd.getCardinality() + ", Count:" + matchCount;
                        throw new SecurityException(GlobalErrIds.DSD_VALIDATION_FAILED, error);
                    }
                }
                else // Check the parents of activated role for DSD match:
                {
                    // Now pull the activated role's list of parents.
                    Set<String> parentSet = RoleUtil.getAscendants(actRole.getName(), session.getContextId());

                    // Iterate over the list of parent roles:
                    for (String parentRole : parentSet)
                    {
                        if (map.contains(parentRole)) // is there match between parent and DSD member?
                        {
                            matchCount++;
                            if (matchCount >= dsd.getCardinality() - 1) // Does the counter exceed max per cardinality on this DSD set?
                            {
                                String error = CLS_NM + ".validateDSD failed for role [" + role.getName() + "] parent role [" + parentRole + "] DSD Set Name:" + dsd.getName() + " Cardinality:" + dsd.getCardinality() + ", Count:" + matchCount;
                                throw new SecurityException(GlobalErrIds.DSD_VALIDATION_FAILED, error);
                            }
                            // Breaking out of the loop here means the DSD algorithm will only match one
                            // role per parent of active role candidate.
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Given DSD entry name, clear its corresponding object values from the cache.
     *
     * @param name contains the name of object to be cleared.
     * @param contextId maps to sub-tree in DIT, for example ou=contextId, dc=jts, dc = com.     *
     * @throws SecurityException in the event of system or rule violation.
     */
    static void clearDsdCacheEntry(String name, String contextId)
        throws SecurityException
    {
        Attribute<String> context = m_dsdCache.getSearchAttribute(CONTEXT_ID);
        Attribute<String> dsdName = m_dsdCache.getSearchAttribute(DSD_NAME);
        Query query = m_dsdCache.createQuery();
        query.includeKeys();
        query.includeValues();
        query.addCriteria(dsdName.eq(name).and(context.eq(contextId)));
        Results results = query.execute();
        for (Result result : results.all())
        {
            m_dsdCache.clear(result.getKey());
        }
    }

    /**
     * Given a role name, return the set of DSD's that have a matching member.
     *
     * @param name contains name of authorized Role used to search the cache.
     * @param contextId maps to sub-tree in DIT, for example ou=contextId, dc=jts, dc = com.
     * @return un-ordered set of matching DSD's.
     * @throws SecurityException in the event of system or rule violation.
     */
    private static Set<SDSet> getDsdCache(String name, String contextId)
        throws SecurityException
    {
        contextId = getContextId(contextId);
        Set<SDSet> finalSet = new HashSet<SDSet>();
        Attribute<String> context = m_dsdCache.getSearchAttribute(CONTEXT_ID);
        Attribute<String> member = m_dsdCache.getSearchAttribute(MEMBER);
        Query query = m_dsdCache.createQuery();
        query.includeKeys();
        query.includeValues();
        query.addCriteria(member.eq(name).and(context.eq(contextId)));
        Results results = query.execute();
        boolean empty = false;
        for (Result result : results.all())
        {
            DsdCacheEntry entry = (DsdCacheEntry) result.getValue();
            if (!entry.isEmpty())
            {
                finalSet.add(entry.getSdSet());
                finalSet = putDsdCache(name, contextId);
            }
            else
            {
                empty = true;
            }
            finalSet.add(entry.getSdSet());
        }
        // If nothing was found in the cache, determine if it needs to be seeded:
        if (finalSet.size() == 0 && !empty)
        {
            finalSet = putDsdCache(name, contextId);
        }
        return finalSet;
    }

    /**
     * Given a Set of authorized Roles, return the set of DSD's that have matching members.
     *
     * @param authorizedRoleSet contains an un-order Set of authorized Roles.
     * @param contextId maps to sub-tree in DIT, for example ou=contextId, dc=jts, dc = com.
     * @return un-ordered set of matching DSD's.
     * @throws SecurityException in the event of system or rule violation.
     */
    static Set<SDSet> getDsdCache(Set<String> authorizedRoleSet, String contextId)
        throws SecurityException
    {
        contextId = getContextId(contextId);
        Set<SDSet> dsdRetSets = new HashSet<SDSet>();
        // Need to proceed?
        if (!VUtil.isNotNullOrEmpty(authorizedRoleSet))
        {
            return dsdRetSets;
        }
        // Was the DSD Cache switched off?
        boolean isCacheDisabled = Config.getBoolean(IS_DSD_CACHE_DISABLED_PARM, false);
        // If so, get DSD's from LDAP:
        if (isCacheDisabled)
        {
            SDSet sdSet = new SDSet();
            sdSet.setType(SDSet.SDType.DYNAMIC);
            sdSet.setContextId(contextId);
            dsdRetSets = sp.search(authorizedRoleSet, sdSet);
        }
        // Search the DSD cache for matching Role members:
        else
        {
            // Search on roleName attribute which maps to 'member' attr on the cache record:
            Attribute<String> member = m_dsdCache.getSearchAttribute(MEMBER);
            Attribute<String> context = m_dsdCache.getSearchAttribute(CONTEXT_ID);
            Query query = m_dsdCache.createQuery();
            query.includeKeys();
            query.includeValues();
            // Add the passed in authorized Role names to this cache query:
            Set<String> roles = new HashSet<String>(authorizedRoleSet);
            query.addCriteria(member.in(roles).and(context.eq(contextId)));
            // Return all DSD cache entries that match roleName to the 'member' attribute in cache entry:
            Results results = query.execute();
            for (Result result : results.all())
            {
                DsdCacheEntry entry = (DsdCacheEntry) result.getValue();
                // Do not add dummy DSD sets to the final list:
                if (!entry.isEmpty())
                {
                    dsdRetSets.add(entry.getSdSet());
                }
                // Remove role member from authorizedRoleSet to preclude from upcoming DSD search:
                authorizedRoleSet.remove(entry.getMember());
            }
            // Authorized roles remaining in this set correspond to missed cache hits from above:
            if (authorizedRoleSet.size() > 0)
            {
                dsdRetSets = putDsdCache(authorizedRoleSet, contextId);
            }
        }
        return dsdRetSets;
    }

    /**
     * Get the matching DSD's from directory and add to the cache (if found).  If matching DSD not found,
     * add dummy entry to cache to prevent repeated searches.
     *
     * @param authorizedRoleSet contains set of Roles used to search directory for matching DSD's.
     * @param contextId maps to sub-tree in DIT, for example ou=contextId, dc=jts, dc = com.
     * @return List of DSD's who have matching Role members.
     * @throws SecurityException in the event of system or rule violation.
     */
    private static Set<SDSet> putDsdCache(Set<String> authorizedRoleSet, String contextId)
        throws SecurityException
    {
        contextId = getContextId(contextId);
        Set<SDSet> dsdSets = new HashSet<SDSet>();
        // Search the DSD's iteratively to seed the DSD cache by Role name:
        for (String roleName : authorizedRoleSet)
        {
            Role role = new Role(roleName);
            role.setContextId(contextId);
            List<SDSet> dsdList = sp.search(role, SDSet.SDType.DYNAMIC);
            if (VUtil.isNotNullOrEmpty(dsdList))
            {
                for (SDSet dsd : dsdList)
                {
                    dsd.setContextId(contextId);
                    Set<String> members = dsd.getMembers();
                    if (members != null)
                    {
                        // Seed the cache with DSD objects mapped to role name:
                        for (String member : members)
                        {
                            String key = buildKey(dsd.getName(), member);
                            DsdCacheEntry entry = new DsdCacheEntry(member, dsd, false);
                            entry.setName(dsd.getName());
                            m_dsdCache.put(getKey(key, contextId), entry);
                        }
                    }
                }
                // Maintain the set of DSD's to be returned to the caller:
                dsdSets.addAll(dsdList);
            }
            else
            {
                // Seed the cache with dummy entry for a Role that is not referenced by DSD:
                String key = buildKey(EMPTY_ELEMENT, roleName);
                SDSet sdSet = new SDSet();
                sdSet.setType(SDSet.SDType.DYNAMIC);
                sdSet.setName(key);
                sdSet.setMember(roleName);
                sdSet.setContextId(contextId);
                DsdCacheEntry entry = new DsdCacheEntry(roleName, sdSet, true);
                entry.setName(key);
                m_dsdCache.put(getKey(sdSet.getName(), contextId), entry);
            }
        }
        return dsdSets;
    }

    /**
     * Get the matching DSD's from directory and add to the cache (if found).  If matching DSD not found,
     * add dummy entry to cache to prevent repeated searches.
     *
     * @param roleName of Role is used to search directory for matching DSD's.
     * @param contextId maps to sub-tree in DIT, for example ou=contextId, dc=jts, dc = com.
     * @return Set of DSD's who have matching Role member.
     * @throws SecurityException in the event of system or rule violation.
     */
    private static Set<SDSet> putDsdCache(String roleName, String contextId)
        throws SecurityException
    {
        contextId = getContextId(contextId);
        Role role = new Role(roleName);
        role.setContextId(contextId);
        List<SDSet> dsdList = sp.search(role, SDSet.SDType.DYNAMIC);
        Set<SDSet> finalSet = new HashSet<SDSet>(dsdList);
        if (VUtil.isNotNullOrEmpty(dsdList))
        {
            for (SDSet dsd : dsdList)
            {
                dsd.setContextId(contextId);
                Set<String> members = dsd.getMembers();
                if (members != null)
                {
                    // Seed the cache with DSD objects mapped to role name:
                    for (String member : members)
                    {
                        String key = buildKey(dsd.getName(), member);
                        DsdCacheEntry entry = new DsdCacheEntry(member, dsd, false);
                        entry.setName(dsd.getName());
                        m_dsdCache.put(getKey(key, contextId), entry);
                    }
                }
            }
        }
        else
        {
            // Seed the cache with dummy entry for Role that does not have DSD:
            String key = buildKey(EMPTY_ELEMENT, roleName);
            SDSet sdSet = new SDSet();
            sdSet.setType(SDSet.SDType.DYNAMIC);
            sdSet.setName(key);
            sdSet.setMember(roleName);
            sdSet.setContextId(contextId);
            DsdCacheEntry entry = new DsdCacheEntry(roleName, sdSet, true);
            entry.setName(key);
            m_dsdCache.put(getKey(sdSet.getName(), contextId), entry);
        }
        return finalSet;
    }

    /**
     * Given entry name, clear its corresponding object value from the cache.
     *
     * @param name contains the name of object to be cleared.
     * @param contextId maps to sub-tree in DIT, for example ou=contextId, dc=jts, dc = com.
     * @throws SecurityException in the event of system or rule violation.
     */
    static void clearSsdCacheEntry(String name, String contextId)
        throws SecurityException
    {
        contextId = getContextId(contextId);
        m_ssdCache.clear(getKey(name, contextId));
    }

    /**
     * Get the matching SSD's from directory and add to the cache (if found).
     *
     * @param name of Role is used to search directory for matching SSD's.
     * @param contextId maps to sub-tree in DIT, for example ou=contextId, dc=jts, dc = com.
     * @return List of SSD's who have matching Role member.
     * @throws SecurityException in the event of system or rule violation.
     */
    private static List<SDSet> putSsdCache(String name, String contextId)
        throws SecurityException
    {
        Role role = new Role(name);
        role.setContextId(contextId);
        List<SDSet> ssdSets = sp.search(role, SDSet.SDType.STATIC);
        m_ssdCache.put(getKey(name, contextId), ssdSets);
        return ssdSets;
    }

    /**
     * Look in cache for matching List of SSD's.
     *
     * @param name of Role is used to search directory for matching SSD's.
     * @param contextId maps to sub-tree in DIT, for example ou=contextId, dc=jts, dc = com.
     * @return List of SSD's who have matching Role member.
     * @throws SecurityException in the event of system or rule violation.
     */
    private static List<SDSet> getSsdCache(String name, String contextId)
        throws SecurityException
    {
        List<SDSet> ssdSets = (List<SDSet>) m_ssdCache.get(getKey(name, contextId));
        if (ssdSets == null)
        {
            ssdSets = putSsdCache(name, contextId);
        }
        return ssdSets;
    }

    /**
     *
     * @param parm1
     * @param parm2
     * @return
     */
    private static String buildKey(String parm1, String parm2)
    {
        return parm1 + ":" + parm2;
    }

    /**
     *
     * @param name
     * @param contextId
     * @return
     */
    private static String getKey(String name, String contextId)
    {
        contextId = getContextId(contextId);
        String szKey = name += ":" + contextId;
        return szKey;
    }

    /**
     *
     * @param contextId
     * @return
     */
    private static String getContextId(String contextId)
    {
        String szContextId = GlobalIds.HOME;
        if(VUtil.isNotNullOrEmpty(contextId) && !contextId.equals(GlobalIds.NULL))
        {
            szContextId = contextId;
        }
        return szContextId;
    }
}