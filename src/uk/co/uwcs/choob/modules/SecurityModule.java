/*
 * ChoobSecurityManager.java
 *
 * Created on June 25, 2005, 3:28 PM
 */

package uk.co.uwcs.choob.modules;

import bsh.classpath.*;
import uk.co.uwcs.choob.support.*;
import java.sql.*;
import java.security.*;
import java.lang.reflect.*;
import java.util.*;
import uk.co.uwcs.choob.support.events.*;

/**
 * Security manager for plugins, access control to anything requiring/checking
 * for a permission.
 *
 * Also manages user, groups etc.
 *
 * @author	bucko
 */
public final class SecurityModule extends SecurityManager // For getClassContext(). Heh.
{
	private DbConnectionBroker dbBroker;
	private Map<Integer,PermissionCollection> nodeMap;
	private Map<Integer,List<Integer>> nodeTree;
	private Map<String,Integer>[] nodeIDCache;
	private Modules mods;
	private int anonID;

	/**
	 * Creates a new instance of SecurityModule
	 * @param dbBroker Database connection pool/broker.
	 */
	SecurityModule(DbConnectionBroker dbBroker, Modules mods)
	{
		// Make sure these classes is preloaded!
		// This avoids circular security checks. Oh, the horror!
		//Class throwAway = bsh.BshMethod.class;
		//throwAway = DiscreteFilesClassLoader.class;

		this.dbBroker = dbBroker;
		this.mods = mods;

		this.nodeDbLock = new Object();

		this.nodeMap = new HashMap<Integer,PermissionCollection>();
		this.nodeTree = new HashMap<Integer,List<Integer>>();

		this.nodeIDCache = new Map[4];
		for(int i=0; i<4; i++)
			nodeIDCache[i] = new HashMap<String,Integer>();

		this.anonID = getNodeIDFromNodeName("anonymous", 3);
	}

	/* =====================
	 * SecurityManager stuff
	 * =====================
	 */

	/**
	 * Returns an AccessControlContext which implies all permissions, but
	 * retains the plugin stack so that getPluginNames() will work.
	 */
	public AccessControlContext getPluginContext( )
	{
		ProtectionDomain domain = new ChoobFakeProtectionDomain( getPluginNames() );
		return new AccessControlContext(new ProtectionDomain[] { domain });
	}

	public ProtectionDomain getProtectionDomain( String pluginName )
	{
		return new ChoobProtectionDomain(this, pluginName);
	}

	public List<String> getPluginNames()
	{
		List<String> pluginStack = new ArrayList<String>();
		// XXX HAX XXX HAX XXX HAX XXX HAX XXX
		// ^^ If this doesn't persuade you that this is a hack, nothing will...
		ChoobSpecialStackPermission perm = new ChoobSpecialStackPermission(pluginStack);
		AccessController.checkPermission(perm);
		perm.patch();
		return pluginStack;
	}

	/**
	 * Gets the name of the nth plugin in the stack (0th = plugin #1).
	 * @param skip
	 * @return null if there is no plugin that far back. Otherwise the plugin
	 *         name.
	 */
	public String getPluginName(int skip)
	{
		List names = getPluginNames();
		if (skip >= names.size())
			return null;
		return (String)names.get(skip);
	}

	/**
	 * Force plugin permissions to be reloaded at some later point.
	 */
	private void invalidateNodePermissions(int nodeID)
	{
		synchronized(nodeMap) {
			nodeMap.remove(nodeID);
		}
	}

	/**
	 * Force plugin tree to be reloaded at some later point.
	 */
	private void invalidateNodeTree(int nodeID)
	{
		synchronized(nodeMap) {
			nodeTree.remove(nodeID);
		}
	}

	private PermissionCollection getNodePermissions(int nodeID)
	{
		PermissionCollection perms;
		synchronized(nodeMap)
		{
			perms = nodeMap.get(nodeID);
			if (perms == null)
			{
				updateNodePermissions(nodeID);
				perms = nodeMap.get(nodeID);
			}
		}
		return perms;
	}

	/**
	 * Update permissions set for the given node ID.
	 * Code visciously hacked out of ChoobSecurityManager.
	 */
	private void updateNodePermissions(int nodeID) {
		Connection dbConnection = null;

		Permissions permissions = new Permissions();

		PreparedStatement permissionsSmt = null;
		try {
			dbConnection=dbBroker.getConnection();
			permissionsSmt = dbConnection.prepareStatement("SELECT Type, Permission, Action FROM UserNodePermissions WHERE UserNodePermissions.NodeID = ?");

			permissionsSmt.setInt(1, nodeID);

			ResultSet permissionsResults = permissionsSmt.executeQuery();

			if ( permissionsResults.first() ) {
				do
				{
					String className = permissionsResults.getString(1);
					String permissionName = permissionsResults.getString(2);
					String actions = permissionsResults.getString(3);

					Class clas;
					try
					{
						clas = this.getClass().forName( className );
					}
					catch (ClassNotFoundException e)
					{
						System.err.println("Permission class not found: " + className);
						continue; // XXX I guess this is OK?
					}

					// Perhaps more strict checking here?
					// TODO - is this check enough to be secure?
					if (!Permission.class.isAssignableFrom(clas))
					{
						System.err.println("Class " + className + " is not a Permission!");
						continue; // XXX
					}
					else if (clas.getClassLoader() instanceof DiscreteFilesClassLoader)
					{
						System.err.println("Class " + className + " is an insecure Permission!");
						continue;
					}

					Constructor con;
					try
					{
						con = clas.getDeclaredConstructor(String.class, String.class);
					}
					catch (NoSuchMethodException e)
					{
						System.err.println("Permission class had no valid constructor: " + className);
						continue; // XXX I guess this is OK?
					}

					Permission perm;
					try
					{
						perm = (Permission)con.newInstance(permissionName, actions);
					}
					catch (IllegalAccessException e)
					{
						System.err.println("Permission class constructor for " + className + " failed: " + e.getMessage());
						e.printStackTrace();
						continue; // XXX
					}
					catch (InstantiationException e)
					{
						System.err.println("Permission class constructor for " + className + " failed: " + e.getMessage());
						e.printStackTrace();
						continue; // XXX
					}
					catch (InvocationTargetException e)
					{
						System.err.println("Permission class constructor for " + className + " failed: " + e.getMessage());
						e.printStackTrace();
						continue; // XXX
					}

					permissions.add(perm);
				} while ( permissionsResults.next() );
			}
		}
		catch ( SQLException e )
		{
			System.err.println("Could not load DB permissions for user node " + nodeID + " (probably now incomplete permissions): " + e.getMessage());
			e.printStackTrace();
		}
		finally
		{
			try
			{
				dbCleanupSel(permissionsSmt, dbConnection);
			}
			catch (ChoobError e) {}
		}

		synchronized(nodeMap) {
			nodeMap.put(nodeID, permissions);
		}
	}

	/**
	 * Check if the numbered user node has a permission
	 * @param permission
	 * @param userNode
	 */
	private boolean hasPerm(Permission permission, int userNode)
	{
		return hasPerm(permission, userNode, false);
	}

	private boolean hasPerm(Permission permission, int userNode, boolean includeThis)
	{
		Iterator<Integer> allNodes = getAllNodes(userNode, includeThis);

		if ( ! allNodes.hasNext() )
		{
			System.out.println("User node " + userNode + " has no subnodes!");
			return false;
		}

		int nodeID;
		while( allNodes.hasNext() )
		{
			nodeID = allNodes.next();
			PermissionCollection perms = getNodePermissions( nodeID );
			// Be careful to avoid invalid groups and stuff.
			if (perms != null && perms.implies(permission))
				return true;
		}

		return false;
	}

	/**
	 * Get all nodes linked to the passed node.
	 */
	private Iterator<Integer> getAllNodes(int nodeID, boolean addThis)
	{
		synchronized(nodeTree)
		{
			Connection dbCon;
			try
			{
				dbCon=dbBroker.getConnection();
			}
			catch (SQLException e)
			{
				e.printStackTrace();
				System.err.println("Couldn't get a connection for getAllNodes()");
				return new ArrayList<Integer>().iterator(); // XXX
			}
			List <Integer>list = new ArrayList<Integer>();
			if (addThis)
				list.add(nodeID);
			try
			{
				getAllNodesRecursive(dbCon, list, nodeID, 0);
				if (anonID != -1)
				{
					list.add(anonID);
					getAllNodesRecursive(dbCon, list, anonID, 0);
				}
			}
			finally
			{
				dbBroker.freeConnection(dbCon);
			}
			return list.iterator();
		}
	}

	private void getAllNodesRecursive(Connection dbConn, List<Integer> list, int nodeID, int recurseDepth)
	{
		if (recurseDepth >= 5)
		{
			System.err.println("Ack! Recursion depth exceeded when trying to process user node " + nodeID);
			return;
		}

		List<Integer> things = nodeTree.get(nodeID);
		if (things == null)
		{
			things = new ArrayList<Integer>();
			nodeTree.put(nodeID, things);
			PreparedStatement stat = null;
			try
			{
				stat = dbConn.prepareStatement("SELECT GroupID FROM GroupMembers WHERE MemberID = ?");
				stat.setInt(1, nodeID);

				ResultSet results = stat.executeQuery();

				if ( results.first() )
				{
					do
					{
						int newNode = results.getInt(1);
						if ( !list.contains( newNode ) )
						{
							list.add( newNode );
						}
						things.add( newNode );
					} while ( results.next() );
				}
			}
			catch (SQLException e)
			{
				sqlErr("getting user nodes", e);
			}
			finally
			{
				try
				{
					if (stat != null)
						stat.close();
				}
				catch (SQLException e)
				{
					sqlErr("cleaning up user node statment", e);
				}
			}
		}

		for(int newNode: things)
		{
			if (!list.contains(newNode))
				list.add(newNode);
			getAllNodesRecursive(dbConn, list, newNode, recurseDepth + 1);
		}
	}

	/**
	 * Get the node ID that corresponds to a node
	 */
	private int getNodeIDFromNode(UserNode node)
	{
		return getNodeIDFromNodeName(node.getName(), node.getType());
	}

	/**
	 * Get the node ID that corresponds to a node name
	 */
	private int getNodeIDFromUserName(UserEvent userEvent)
	{
		if (userEvent instanceof IRCRootEvent)
			checkEvent((IRCRootEvent)userEvent);
		return getNodeIDFromNodeName(userEvent.getNick(), 0);
	}

	/**
	 * Get the node ID that corresponds to a plugin
	 */
	private int getNodeIDFromPluginName(String pluginName)
	{
		return getNodeIDFromNodeName(pluginName, 2);
	}

	/**
	 * Get the node ID that corresponds to a node name
	 */
	private int getNodeIDFromNodeName(String nodeName, int nodeType)
	{
		// Check the cache.
		Integer id = nodeIDCache[nodeType].get(nodeName.toLowerCase());
		if (id != null)
			return id.intValue();

		Connection dbConn = null;
		PreparedStatement stat = null;
		try
		{
			dbConn = dbBroker.getConnection();
			stat = dbConn.prepareStatement("SELECT NodeID FROM UserNodes WHERE NodeName = ? && NodeClass = ?");
			stat.setString(1, nodeName);
			stat.setInt(2, nodeType);
			ResultSet results = stat.executeQuery();
			if ( results.first() )
			{
				int idGot = results.getInt(1);
				nodeIDCache[nodeType].put(nodeName.toLowerCase(), idGot);
				return idGot;
			}
			System.err.println("Ack! Node name " + nodeName + "(" + nodeType + ") not found!");
		}
		catch (SQLException e)
		{
			sqlErr("getting a node ID", e);
		}
		finally
		{
			dbCleanupSel(stat, dbConn);
		}
		return -1;
	}

	/**
	 * Get the node ID that corresponds to a node name
	 */
	private UserNode getNodeFromNodeID(int nodeID)
	{
		Connection dbConn = null;
		PreparedStatement stat = null;
		try
		{
			dbConn = dbBroker.getConnection();
			stat = dbConn.prepareStatement("SELECT NodeName, NodeClass FROM UserNodes WHERE NodeID = ?");
			stat.setInt(1, nodeID);
			ResultSet results = stat.executeQuery();
			if ( results.first() )
			{
				return new UserNode(results.getString(1), results.getInt(2));
			}
			System.err.println("Ack! Node " + nodeID + " not found!");
		}
		catch (SQLException e)
		{
			System.err.println("Ack! SQL exception when getting node from node ID " + nodeID + ": " + e);
		}
		finally
		{
			dbCleanupSel(stat, dbConn);
		}
		return null;
	}

	/**
	 * Get the last insert ID
	 */
	private int getLastInsertID(Connection dbConn) throws SQLException
	{
		PreparedStatement stat = null;
		try
		{
			stat = dbConn.prepareStatement("SELECT LAST_INSERT_ID()");
			ResultSet results = stat.executeQuery();
			if ( results.first() )
				return results.getInt(1);
			throw new SQLException("Ack! LAST_INSERT_ID() returned no results!");
		}
		finally
		{
			stat.close();
		}
	}


	/* ================================
	 * PLUGIN PERMISSION CHECK ROUTINES
	 * ================================
	 */

	public String renderPermission(Permission permission)
	{
		if (permission instanceof AllPermission)
			return "ALL";

		String output;
		String className = permission.getClass().getSimpleName();
		if (className.endsWith("Permission"))
			output = className.substring(0, className.length() - 10);
		else
			output = className;

		String name = permission.getName();
		String actions = permission.getActions();
		if (name != null)
		{
			output += " with name \"" + name + "\"";
			if (actions != null)
				output += " and actions \"" + actions + "\"";
		}
		else if (actions != null)
			output += " and actions \"" + actions + "\"";

		return output;
	}

	/**
	 * Check if the given nickName is authed with NickServ (if NickServ is loaded).
	 * @param userEvent The event to validate and check the permission on.
	 * @throws ChoobNSAuthError If the nick is not authorised.
	 */
	public void checkNS(UserEvent userEvent) throws ChoobNSAuthError
	{
		if (!hasNS(userEvent))
			throw new ChoobNSAuthError();
	}

	/**
	 * Check if the given nickName is authed with NickServ (if NickServ is loaded).
	 * @param userEvent The event to validate and check the permission on.
	 * @return Whether the nick is authorised.
	 */
	public boolean hasNS(UserEvent userEvent)
	{
		try
		{
			if (userEvent instanceof IRCRootEvent)
				checkEvent((IRCRootEvent)userEvent);

			//return (Boolean)mods.plugin.callAPI("NickServ", "Check", nickName, false);
			return (Boolean)mods.plugin.callAPI("NickServ", "Check", userEvent.getNick());
		}
		catch (ChoobNoSuchPluginException e)
		{
			// XXX Should this throw an exception?:
			//if (!allowNoNS)
			//	throw new ChoobAuthError("The NickServ plugin is not loaded! Holy mother of God save us all!");
			return true;
		}
		catch (ChoobException e)
		{
			// OMFG!
			System.err.println("Error calling NickServ check! Details:");
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Check if the given nickName has permission and is authed with NickServ (if NickServ is loaded).
	 * @param permission The permission to check.
	 * @param userEvent The event to validate and check the permission on.
	 * @throws ChoobAuthError If the nick is not authorised.
	 */
	public void checkNickPerm(Permission permission, UserEvent userEvent) throws ChoobAuthError
	{
		checkNS(userEvent);

		if (!hasPerm(permission, userEvent))
			throw new ChoobUserAuthError(permission);
	}

	/**
	 * Check if the given nickName has permission and is authed with NickServ (if NickServ is loaded).
	 * @param permission The permission to check.
	 * @param userEvent The event to validate and check the permission on.
	 * @return Whether the nick is authorised.
	 */
	public boolean hasNickPerm(Permission permission, UserEvent userEvent)
	{
		// XXX: Check for synthetic userEvent here.
		
		if (!hasNS(userEvent))
			return false;

		if (!hasPerm(permission, userEvent))
			return false;

		return true;
	}

	/**
	 * Check if the given userName has permission. Better to use checkNickPerm.
	 * @param permission The permission to check.
	 * @param userEvent The event to validate and check the permission on.
	 */
	public void checkPerm(Permission permission, UserEvent userEvent) throws ChoobUserAuthError
	{
		if (!hasPerm(permission, userEvent))
			throw new ChoobUserAuthError(permission);
	}

	/**
	 * Check if the given userName has permission. Better to use checkNickPerm.
	 * @param permission The permission to check.
	 * @param userEvent The event to validate and check the permission on.
	 */
	public boolean hasPerm(Permission permission, UserEvent userEvent)
	{
		// XXX: Check for synthetic userEvent here.
		
		int userNode = getNodeIDFromUserName(userEvent);

		System.out.println("Checking permission on user " + userEvent.getNick() + "(" + userNode + ")" + ": " + permission);

		if (userNode == -1)
			if (anonID != -1)
				return hasPerm(permission, anonID, true);

		return hasPerm(permission, userNode);
	}

	/**
	 * Check if the previous plugin on the call stack has a permission.
	 * @param permission Permission to query
	 * @throws ChoobPluginAuthError if the permission has not been granted.
	 */
	public void checkPluginPerm(Permission permission) throws ChoobPluginAuthError
	{
		String plugin = getPluginName(0);
		checkPluginPerm(permission, plugin);
	}

	/**
	 * Check if the previous plugin on the call stack has a permission
	 * @param permission Permission to query
	 */
	public boolean hasPluginPerm(Permission permission)
	{
		return hasPluginPerm(permission, getPluginName(0));
	}

	/**
	 * Check if the previous plugin on the call stack has a permission.
	 * @param permission Permission to query
	 * @param skip Number of plugins to skip
	 * @throws ChoobPluginAuthError if the permission has not been granted.
	 */
	public void checkPluginPerm(Permission permission, int skip) throws ChoobPluginAuthError
	{
		String plugin = getPluginName(skip);
		checkPluginPerm(permission, plugin);
	}

	/**
	 * Check if the skip'th plugin on the call stack has a permission
	 * @param permission Permission to query
	 * @param skip Number of plugins to skip
	 */
	public boolean hasPluginPerm(Permission permission, int skip)
	{
		return hasPluginPerm(permission, getPluginName(skip));
	}

	public String getCallerPluginName()
	{
		return getPluginName(1);
	}

	/**
	 * Check if the previous plugin on the call stack has a permission.
	 * @param permission Permission to query
	 * @param plugin Plugin to query
	 * @throws ChoobPluginAuthError if the permission has not been granted.
	 */
	public void checkPluginPerm(Permission permission, String plugin) throws ChoobPluginAuthError
	{
		if (!hasPluginPerm(permission, plugin))
			throw new ChoobPluginAuthError(plugin, permission);
	}

	/**
	 * Check if the passed plugin has a permission
	 * @param permission Permission to query
	 * @param plugin Plugin to query
	 */
	public boolean hasPluginPerm(final Permission permission, final String plugin)
	{
		if (plugin == null)
			return true; // XXX should this be true?

		// All plugins should be allowed to read the following properties:
		//   line.separator
		if (permission instanceof PropertyPermission)
		{
			PropertyPermission propPerm = (PropertyPermission)permission;
			if (propPerm.getActions().equals("read"))
			{
				if (propPerm.getName().equals("line.separator"))
					return true;
			}
		}

		// Should prevent circular checks...
		boolean rv = (AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
			public Boolean run() {
				int nodeID = getNodeIDFromPluginName( plugin );

				// No such user!
				if (nodeID == -1)
					return false;

				// Now just check on this node!
				// Include the plugin.
				return hasPerm( permission, nodeID, true );
			}
		})).booleanValue();

		if (!rv) {
			System.out.println("Plugin " + plugin + " lacks permission " + permission + ".");
		}
		return rv;
	}

	/* ===============================
	 * PLUGIN USER MANAGEMENT ROUTINES
	 * ===============================
	 */

	private Object nodeDbLock;

	/**
	 * Convenience method
	 */
	private void dbCleanupSel(Statement stat, Connection dbConn)
	{
		try
		{
			if (stat != null)
				stat.close();
		}
		catch (SQLException e)
		{
			sqlErr("closing SQL statement", e);
		}
		finally
		{
			dbBroker.freeConnection(dbConn);
		}
	}

	private void dbCleanup(Statement stat, Connection dbConn)
	{
		try
		{
			if (stat != null)
				stat.close();
		}
		catch (SQLException e)
		{
			sqlErr("closing SQL statement", e);
		}
		finally
		{
			try
			{
				dbConn.rollback(); // If success, this does nothing
				dbConn.setAutoCommit(true);
				dbBroker.freeConnection(dbConn);
			}
			catch (SQLException e)
			{
				// XXX WTF to do here?
				sqlErr("cleaning up SQL connection", e);
			}
		}
	}

	/**
	 * Convenience method
	 */
	private void sqlErr(String task, SQLException e)
	{
		System.err.println("ACK! SQL error when " + task + ": " + e);
		throw new ChoobError("An SQL error occurred when " + task + ". Please ask the bot administrator to check the logs.");
	}

	/**
	 * Add a plugin->user binding to the database
	 * @deprecated Perhaps?
	 */
	// TODO: Should this simply add plugin.pluginName to user.userName?
	public void bindPlugin(String pluginName, UserEvent userEvent) throws ChoobException
	{
		AccessController.checkPermission(new ChoobPermission("plugin.bind"));

		int userID = getNodeIDFromUserName(userEvent);
		if ( userID == -1 )
		{
			throw new ChoobException("User " + userEvent.getNick() + " does not exist!");
		}

		Connection dbConn = null;
		PreparedStatement stat = null;
		synchronized(nodeDbLock)
		{
			try
			{
				dbConn = dbBroker.getConnection();
				// Bind plugin
				stat = dbConn.prepareStatement("REPLACE INTO UserPlugins (UserID, PluginName) VALUES (?, ?)");
				stat.setInt(1, userID);
				stat.setString(2, pluginName);
				if (stat.executeUpdate() == 0)
					System.err.println("Ack! No rows updated in plugin bind!");

				// Done!
				dbConn.commit();
			}
			catch (SQLException e)
			{
				sqlErr("binding plugin " + pluginName, e);
			}
			finally
			{
				dbCleanupSel(stat, dbConn);
			}
		}
	}
	// Must check (system) user.add

	/**
	 * Add a user to the database
	 */
	public void addUser(String userName) throws ChoobException
	{
		AccessController.checkPermission(new ChoobPermission("user.add"));

		Connection dbConn = null;
		PreparedStatement stat = null;
		synchronized(nodeDbLock)
		{
			try
			{
				dbConn = dbBroker.getConnection();
				dbConn.setAutoCommit(false);
				// First, make sure no user exists...
				stat = dbConn.prepareStatement("SELECT NodeID FROM UserNodes WHERE NodeName = ? AND (NodeClass = 0 OR NodeClass = 1)");
				stat.setString(1, userName);
				ResultSet results = stat.executeQuery();
				if ( results.first() )
				{
					throw new ChoobException ("User " + userName + " already exists!");
				}
				stat.close();

				// Add user and group
				stat = dbConn.prepareStatement("INSERT INTO UserNodes (NodeName, NodeClass) VALUES (?, ?)");
				stat.setString(1, userName);
				stat.setInt(2, 0);
				if (stat.executeUpdate() == 0)
					System.err.println("Ack! No rows updated in user insert!");
				int userID = getLastInsertID(dbConn);
				stat.close();

				stat = dbConn.prepareStatement("INSERT INTO UserNodes (NodeName, NodeClass) VALUES (?, ?)");
				stat.setString(1, userName);
				stat.setInt(2, 1);
				if (stat.executeUpdate() == 0)
					System.err.println("Ack! No rows updated in user group insert!");
				int groupID = getLastInsertID(dbConn);
				stat.close();

				// Now bind them.
				stat = dbConn.prepareStatement("INSERT INTO GroupMembers (GroupID, MemberID) VALUES (?, ?)");
				stat.setInt(1, groupID);
				stat.setInt(2, userID);
				if (stat.executeUpdate() == 0)
					System.err.println("Ack! No rows updated in user group member insert!");

				// Done!
				dbConn.commit();
			}
			catch (SQLException e)
			{
				sqlErr("adding user " + userName, e);
			}
			finally
			{
				dbCleanup(stat, dbConn); // If success, this does nothing
			}
		}
	}

	/**
	 * Links a user name to a root user name.
	 */
	public void linkUser(String root, String leaf) throws ChoobException
	{
		AccessController.checkPermission(new ChoobPermission("user.link"));

		Connection dbConn = null;
		PreparedStatement stat = null;
		synchronized(nodeDbLock)
		{
			try
			{
				dbConn = dbBroker.getConnection();
				dbConn.setAutoCommit(false);
				// First, make sure no user exists...
				stat = dbConn.prepareStatement("SELECT NodeID FROM UserNodes WHERE NodeName = ? AND NodeClass = 0");
				stat.setString(1, leaf);
				ResultSet results = stat.executeQuery();
				if ( results.first() )
					throw new ChoobException ("User " + leaf + " already exists!");
				stat.close();

				// Now make sure the root does exist...
				// As user
				stat = dbConn.prepareStatement("SELECT NodeID FROM UserNodes WHERE NodeName = ? AND NodeClass = 0");
				stat.setString(1, root);
				results = stat.executeQuery();
				if ( !results.first() )
					throw new ChoobException ("User " + root + " does not exist!");
				int rootUserID = results.getInt(1);
				stat.close();

				// As group
				stat = dbConn.prepareStatement("SELECT NodeID FROM UserNodes WHERE NodeName = ? AND NodeClass = 1");
				stat.setString(1, root);
				results = stat.executeQuery();
				if ( !results.first() )
					throw new ChoobException ("User " + root + " is a leaf user. You can't link to it!");
				int rootID = results.getInt(1);
				stat.close();

				// And that they're linked.
				stat = dbConn.prepareStatement("SELECT GroupID FROM GroupMembers WHERE GroupID = ? AND MemberID = ?");
				stat.setInt(1, rootID);
				stat.setInt(2, rootUserID);
				results = stat.executeQuery();
				if ( !results.first() )
					throw new ChoobException ("User " + root + " is a leaf user. You can't link to it!");
				stat.close();

				// Add user.
				stat = dbConn.prepareStatement("INSERT INTO UserNodes (NodeName, NodeClass) VALUES (?, ?)");
				stat.setString(1, leaf);
				stat.setInt(2, 0);
				if (stat.executeUpdate() == 0)
					System.err.println("Ack! No rows updated in user insert!");
				int userID = getLastInsertID(dbConn);
				stat.close();

				// Now bind it.
				stat = dbConn.prepareStatement("INSERT INTO GroupMembers (GroupID, MemberID) VALUES (?, ?)");
				stat.setInt(1, rootID);
				stat.setInt(2, userID);
				if (stat.executeUpdate() == 0)
					System.err.println("Ack! No rows updated in user group member insert!");

				// Done!
				dbConn.commit();
			}
			catch (SQLException e)
			{
				sqlErr("linking user " + leaf + " to root " + root, e);
			}
			finally
			{
				dbCleanup(stat, dbConn); // If success, this does nothing
			}
		}
	}

	/**
	 * Get the "root" username for a given user.
	 * @return the root username, or null
	 */
	public String getRootUser(String userName)
	{
		Connection dbConn = null;
		PreparedStatement stat = null;
		try
		{
			dbConn = dbBroker.getConnection();
			// First, make sure no user exists...
			stat = dbConn.prepareStatement("SELECT NodeID FROM UserNodes WHERE NodeName = ? AND NodeClass = 0");
			stat.setString(1, userName);
			ResultSet results = stat.executeQuery();
			if ( !results.first() )
				//throw new ChoobException ("User " + userName + " does not exist!");
				return null;
				//return userName;
			int userID = results.getInt(1);
			stat.close();

			stat = dbConn.prepareStatement("SELECT GroupID FROM GroupMembers WHERE MemberID = ?");
			stat.setInt(1, userID);
			results = stat.executeQuery();
			if ( !results.first() )
				throw new ChoobError ("Consistency error: User " + userName + " is in no group!");
			int groupID = results.getInt(1);
			if ( results.next() )
				throw new ChoobError ("Consistency error: User " + userName + " is in more than one group!");
			stat.close();

			// Now make sure the root does exist...
			stat = dbConn.prepareStatement("SELECT NodeName FROM UserNodes WHERE NodeID = ?");
			stat.setInt(1, groupID);
			results = stat.executeQuery();
			if ( !results.first() )
				throw new ChoobError ("Consistency error: Group " + groupID + " does not exist!");
			String groupName = results.getString(1);
			stat.close();

			return groupName;
		}
		catch (SQLException e)
		{
			sqlErr("fetching root user name for " + userName, e);
		}
		finally
		{
			dbCleanupSel(stat, dbConn); // If success, this does nothing
		}
		return null; // Impossible to get here anyway...
	}

	/**
	 * Removes a user name (but not its groups).
	 */
	public void delUser(String userName) throws ChoobException
	{
		AccessController.checkPermission(new ChoobPermission("user.del"));

		Connection dbConn = null;
		PreparedStatement stat = null;
		synchronized(nodeDbLock)
		{
			try
			{
				dbConn = dbBroker.getConnection();
				dbConn.setAutoCommit(false);
				// Make sure the user exists...
				stat = dbConn.prepareStatement("SELECT NodeID FROM UserNodes WHERE NodeName = ? AND NodeClass = 0");
				stat.setString(1, userName);
				ResultSet results = stat.executeQuery();
				if ( !results.first() )
					throw new ChoobException ("User " + userName + " does not exist!");
				int userID = results.getInt(1);
				stat.close();

				// First, unbind the user.
				stat = dbConn.prepareStatement("DELETE FROM GroupMembers WHERE MemberID = ?");
				stat.setInt(1, userID);
				if (stat.executeUpdate() == 0)
					System.err.println("Ack! No rows updated in user member delete!");

				// Delete user.
				stat = dbConn.prepareStatement("DELETE FROM UserNodes WHERE NodeID = ?");
				stat.setInt(1, userID);
				if (stat.executeUpdate() == 0)
					System.err.println("Ack! No rows updated in user delete!");
				stat.close();

				// Done!
				dbConn.commit();
			}
			catch (SQLException e)
			{
				sqlErr("deleting user " + userName, e);
			}
			finally
			{
				dbCleanup(stat, dbConn); // If success, this does nothing
			}
		}
	}

	/**
	 * Add a user to the database
	 */
	public void addGroup(String groupName) throws ChoobException
	{
		UserNode group = new UserNode(groupName);

		if (group.getType() == 2) // plugins can poke their own groups!
		{
			String pluginName = getPluginName(0);
			if (!(group.getRootName().compareToIgnoreCase(pluginName)==0))
				AccessController.checkPermission(new ChoobPermission("group.add."+groupName));
		}
		else
		{
			AccessController.checkPermission(new ChoobPermission("group.add."+groupName));
		}

		// OK, we're allowed to add.
		Connection dbConn = null;
		PreparedStatement stat = null;
		synchronized(nodeDbLock)
		{
			try
			{
				dbConn = dbBroker.getConnection();
				dbConn.setAutoCommit(false);
				stat = dbConn.prepareStatement("SELECT NodeID FROM UserNodes WHERE NodeName = ? AND NodeClass = ?");
				stat.setString(1, group.getName());
				stat.setInt(2, group.getType());
				ResultSet results = stat.executeQuery();
				if ( results.first() )
				{
					throw new ChoobException("Group " + groupName + " already exists!");
				}
				stat.close();

				stat = dbConn.prepareStatement("INSERT INTO UserNodes (NodeName, NodeClass) VALUES (?, ?)");
				stat.setString(1, group.getName());
				stat.setInt(2, group.getType());
				if (stat.executeUpdate() == 0)
					System.err.println("Ack! No rows updated in group " + groupName + " insert!");
				dbConn.commit();
			}
			catch (SQLException e)
			{
				sqlErr("adding group " + groupName, e);
			}
			finally {
				dbCleanup(stat, dbConn); // If success, this does nothing
			}
		}
	}

	public void addUserToGroup(String parentName, String childName) throws ChoobException
	{
		UserNode parent = new UserNode(parentName);
		UserNode child = new UserNode(childName, true);
		addNodeToNode(parent, child);
	}

	public void addGroupToGroup(String parentName, String childName) throws ChoobException
	{
		UserNode parent = new UserNode(parentName);
		UserNode child = new UserNode(childName);
		addNodeToNode(parent, child);
	}

	public void addNodeToNode(UserNode parent, UserNode child) throws ChoobException
	{
		if (parent.getType() == 2) // plugins can poke their own groups!
		{
			String pluginName = getPluginName(0);
			if (!(parent.getRootName().compareToIgnoreCase(pluginName)==0))
				AccessController.checkPermission(new ChoobPermission("group.members."+parent));
		}
		else
		{
			AccessController.checkPermission(new ChoobPermission("group.members."+parent));
		}

		// OK, we're allowed to add.
		int parentID = getNodeIDFromNode(parent);
		int childID = getNodeIDFromNode(child);
		if (parentID == -1)
			throw new ChoobException("Group " + parent + " does not exist!");
		if (childID == -1)
			throw new ChoobException("Group " + child + " does not exist!");

		Connection dbConn = null;
		PreparedStatement stat = null;
		synchronized(nodeDbLock)
		{
			try
			{
				dbConn = dbBroker.getConnection();
				dbConn.setAutoCommit(false);
				stat = dbConn.prepareStatement("SELECT MemberID FROM GroupMembers WHERE GroupID = ? AND MemberID = ?");
				stat.setInt(1, parentID);
				stat.setInt(2, childID);
				ResultSet results = stat.executeQuery();
				if ( results.first() )
				{
					throw new ChoobException("Group " + parent + " already had member " + child);
				}
				stat.close();

				stat = dbConn.prepareStatement("INSERT INTO GroupMembers (GroupID, MemberID) VALUES (?, ?)");
				stat.setInt(1, parentID);
				stat.setInt(2, childID);
				if ( stat.executeUpdate() == 0 )
					System.err.println("Ack! Group member add did nothing: " + parent + ", member " + child);

				dbConn.commit();
			}
			catch (SQLException e)
			{
				sqlErr("adding " + child + " to group " + parent, e);
			}
			finally
			{
				dbCleanup(stat, dbConn); // If success, this does nothing
			}
		}
		invalidateNodeTree(childID);
	}

	public void removeUserFromGroup(String parentName, String childName) throws ChoobException
	{
		UserNode parent = new UserNode(parentName);
		UserNode child = new UserNode(childName, true);
		removeNodeFromNode(parent, child);
	}

	public void removeGroupFromGroup(String parentName, String childName) throws ChoobException
	{
		UserNode parent = new UserNode(parentName);
		UserNode child = new UserNode(childName);
		removeNodeFromNode(parent, child);
	}

	public void removeNodeFromNode(UserNode parent, UserNode child) throws ChoobException
	{
		if (parent.getType() == 2) // plugins can poke their own groups!
		{
			String pluginName = getPluginName(0);
			if (!(parent.getRootName().compareToIgnoreCase(pluginName)==0))
				AccessController.checkPermission(new ChoobPermission("group.members."+parent));
		}
		else
		{
			AccessController.checkPermission(new ChoobPermission("group.members."+parent));
		}

		// OK, we're allowed to add.
		int parentID = getNodeIDFromNode(parent);
		int childID = getNodeIDFromNode(child);
		if (parentID == -1)
			throw new ChoobException("Group " + parent + " does not exist!");
		if (childID == -1)
			throw new ChoobException("Group " + child + " does not exist!");

		Connection dbConn = null;
		PreparedStatement stat = null;
		synchronized(nodeDbLock)
		{
			try
			{
				dbConn = dbBroker.getConnection();
				stat = dbConn.prepareStatement("SELECT MemberID FROM GroupMembers WHERE GroupID = ? AND MemberID = ?");
				stat.setInt(1, parentID);
				stat.setInt(2, childID);
				ResultSet results = stat.executeQuery();
				if ( ! results.first() )
				{
					throw new ChoobException("Group " + parent + " did not have member " + child);
				}
				stat.close();

				stat = dbConn.prepareStatement("DELETE FROM GroupMembers WHERE GroupID = ? AND  MemberID = ?");
				stat.setInt(1, parentID);
				stat.setInt(2, childID);
				if ( stat.executeUpdate() == 0 )
					System.err.println("Ack! Group member remove did nothing: " + parent + ", member " + child);
			}
			catch (SQLException e)
			{
				sqlErr("removing " + child + " from " + parent, e);
			}
			finally
			{
				dbCleanupSel(stat, dbConn); // If success, this does nothing
			}
		}
		invalidateNodeTree(childID);
	}

	public void grantPermission(String groupName, Permission permission) throws ChoobException
	{
		UserNode group = new UserNode(groupName);
		if (group.getType() == 2) // plugins can add their own permissions (kinda)
		{
			String pluginName = getPluginName(0);
			if (!(group.getRootName().compareToIgnoreCase(pluginName)==0))
				AccessController.checkPermission(new ChoobPermission("group.grant."+group));
			// OK, that's all fine, BUT:
			if (!hasPluginPerm(permission))
			{
				System.err.println("Plugin " + pluginName + " tried to grant permission " + permission + " it didn't have!");
				throw new ChoobException("A plugin may only grant permssions which it is entitled to.");
			}
		}
		else
		{
			AccessController.checkPermission(new ChoobPermission("group.grant."+group));
		}


		// OK, we're allowed to add.
		int groupID = getNodeIDFromNode(group);
		if (groupID == -1)
			throw new ChoobException("Group " + group + " does not exist!");

		if (hasPerm(permission, groupID, true))
			throw new ChoobException("Group " + group + " already has permission " + permission + "!");

		Connection dbConn = null;
		PreparedStatement stat = null;
		synchronized(nodeDbLock)
		{
			try
			{
				dbConn = dbBroker.getConnection();
				stat = dbConn.prepareStatement("INSERT INTO UserNodePermissions (NodeID, Type, Permission, Action) VALUES (?, ?, ?, ?)");
				stat.setInt(1, groupID);
				stat.setString(2, permission.getClass().getName());
				if (permission instanceof AllPermission)
				{
					stat.setString(3, "");
					stat.setString(4, "");
				}
				else
				{
					stat.setString(3, permission.getName());
					stat.setString(4, permission.getActions());
				}
				if ( stat.executeUpdate() == 0 )
					System.err.println("Ack! Permission add did nothing: " + group + " " + permission);

				invalidateNodePermissions(groupID);
			}
			catch (SQLException e)
			{
				sqlErr("adding permission " + permission + " to group " + group, e);
			}
			finally
			{
				dbCleanupSel(stat, dbConn); // If success, this does nothing
			}
		}
	}

	/**
	 * Attempt to work out from whence a group's permissions come.
	 */
	public String[] findPermission(String groupName, Permission permission) throws ChoobException
	{
		UserNode group = new UserNode(groupName);
		int groupID = getNodeIDFromNode(group);
		if (groupID == -1)
			throw new ChoobException("Group " + group + " does not exist!");

		List<String> foundPerms = new LinkedList<String>();

		Iterator<Integer> allNodes = getAllNodes(groupID, true);

		if ( ! allNodes.hasNext() )
		{
			return new String[0];
		}

		int nodeID;
		while( allNodes.hasNext() )
		{
			nodeID = allNodes.next();
			PermissionCollection perms = getNodePermissions( nodeID );
			// Be careful to avoid invalid groups and stuff.
			if (perms != null && perms.implies(permission))
			{
				// Which element?
				Enumeration<Permission> allPerms = perms.elements();
				while( allPerms.hasMoreElements() )
				{
					Permission perm = allPerms.nextElement();
					if (perm.implies(permission))
					{
						foundPerms.add(getNodeFromNodeID(nodeID).toString() + perm);
					}
				}
			}
		}
		String[] retVal = new String[foundPerms.size()];
		return foundPerms.toArray(retVal);
	}

	/**
	 * Get a list of permissions for a given group.
	 */
	public String[] getPermissions(String groupName) throws ChoobException
	{
		UserNode group = new UserNode(groupName);
		int groupID = getNodeIDFromNode(group);
		if (groupID == -1)
			throw new ChoobException("Group " + group + " does not exist!");

		List<String> foundPerms = new LinkedList<String>();

		PermissionCollection perms = getNodePermissions( groupID );
		// Be careful to avoid invalid groups and stuff.
		if (perms != null)
		{
			// Which element?
			Enumeration<Permission> allPerms = perms.elements();
			while( allPerms.hasMoreElements() )
			{
				Permission perm = allPerms.nextElement();
				foundPerms.add(perm.toString());
			}
		}
		String[] retVal = new String[foundPerms.size()];
		return foundPerms.toArray(retVal);
	}

	public void revokePermission(String groupName, Permission permission) throws ChoobException
	{
		UserNode group = new UserNode(groupName);
		if (group.getType() == 2) // plugins can revoke their own permissions
		{
			String pluginName = getPluginName(0);
			if (!(group.getRootName().compareToIgnoreCase(pluginName)==0))
				AccessController.checkPermission(new ChoobPermission("group.revoke."+group));
		}
		else
		{
			AccessController.checkPermission(new ChoobPermission("group.revoke."+group));
		}


		// OK, we're allowed to add.
		int groupID = getNodeIDFromNode(group);
		if (groupID == -1)
			throw new ChoobException("Group " + group + " does not exist!");

		if (!hasPerm(permission, groupID, true))
			throw new ChoobException("Group " + group + " does not have permission " + permission + "!");

		Connection dbConn = null;
		PreparedStatement stat = null;
		synchronized(nodeDbLock)
		{
			try
			{
				dbConn = dbBroker.getConnection();
				if (permission instanceof AllPermission)
				{
					stat = dbConn.prepareStatement("DELETE FROM UserNodePermissions WHERE NodeID = ? AND Type = ?");
					stat.setInt(1, groupID);
					stat.setString(2, permission.getClass().getName());
				}
				else
				{
					stat = dbConn.prepareStatement("DELETE FROM UserNodePermissions WHERE NodeID = ? AND Type = ? AND Permission = ? AND Action = ?");
					stat.setInt(1, groupID);
					stat.setString(2, permission.getClass().getName());
					stat.setString(3, permission.getName());
					stat.setString(4, permission.getActions());
				}
				if ( stat.executeUpdate() == 0 )
				{
					// This is an ERROR here, not a warning
					throw new ChoobException("The given permission wasn't explicily assigned in the form you attempted to revoke. Try using the find permission command to locate it.");
				}

				invalidateNodePermissions(groupID);
			}
			catch (SQLException e)
			{
				sqlErr("revoking permission " + permission + " from group " + group, e);
			}
			finally
			{
				dbCleanupSel(stat, dbConn); // If success, this does nothing
			}
		}
	}

	private void checkEvent(IRCRootEvent e) throws ChoobEventExpired
	// This should probably accept a generic form of all events.
	{
		if ((new java.util.Date()).getTime()-e.getMillis()>5000)
		{
			if (e instanceof MessageEvent)
				throw new ChoobEventExpired("Security exception: " + e.getClass().getName() + " from " + new java.util.Date(e.getMillis()).toString() + " ('" + ((MessageEvent)e).getMessage() + "') has expired." );
			else
				throw new ChoobEventExpired("Security exception: " + e.getClass().getName() + " from " + new java.util.Date(e.getMillis()).toString() + " has expired." );
		}
	}

}
