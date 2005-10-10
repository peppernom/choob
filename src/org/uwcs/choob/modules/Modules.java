/*
 * ModuleGroup.java
 *
 * Created on June 16, 2005, 7:26 PM
 */

package org.uwcs.choob.modules;

import org.uwcs.choob.support.*;
import org.uwcs.choob.plugins.*;
import org.uwcs.choob.*;
import java.util.*;

/**
 * Wrapper for the group of Modules in the bot.
 * An instance of this class gets passed to the plugins with each call, and is avaliable at most locations in the core, the idea being to provide a consistent set of relevant library functions.
 * @author sadiq
 */
public class Modules
{

	/**
	 * An instance of the PluginModule, used for loading and otherwise interacting with other plugins.
	 */
	public PluginModule plugin;

	/**
	 * An instance of the HistoryModule, used for saving and retrieving logs.
	 */
	public HistoryModule history;

	/**
	 * An instance of the UtilModule, a set of utility functions to remove some repetitive tasks from plugins' code.
	 */
	public UtilModule util;

	/**
	 * An instance of the NickModule, an implementation of nicklinking. This should not be used for anything involving security.
	 */
	public NickModule nick;

	/**
	 * An instance of the IntervalModule, used for creating interval (callback) events.
	 */
	public IntervalModule interval;

	/**
	 * An instance of the SyntheticModule, used for generating synthetic events.
	 */
	public SyntheticModule synthetic;

	/**
	 * An instance of the SecurityModule, used for managing security related tasks.
	 */
	public SecurityModule security;

	/**
	 * An instance of the ProtectedChannels, used for limiting what the bot can do in certain channels.
	 */
	public ProtectedChannels pc;

	/**
	 * An instance of the ObjectDbModule, an interface to the generic ObjectDatabase, for the persistance of objects.
	 */
	public ObjectDbModule odb;

	private DbConnectionBroker dbBroker;
	Map pluginMap;
	List intervalList;
	List filterList;
	private Choob bot;


	/**
	 * Creates a new instance of the Modules.
	 */
	public Modules( DbConnectionBroker dbBroker, Map pluginMap, List <Filter>filterList, List<Interval> intervalList, Choob bot, IRCInterface irc )
	{
		try
		{
			plugin = new PluginModule(pluginMap, dbBroker, filterList, this, irc);
			history = new HistoryModule(dbBroker);
			util = new UtilModule(irc);
			nick = new NickModule(dbBroker);
			interval = new IntervalModule( intervalList, this );
			synthetic = new SyntheticModule( bot );
			security = new SecurityModule( dbBroker );
			pc = new ProtectedChannels();
			odb = new ObjectDbModule( dbBroker, this );
		}
		catch (ChoobException e)
		{
			throw new RuntimeException("Could not instantiate modules: " + e);
		}

		this.dbBroker = dbBroker;
		this.pluginMap = pluginMap;
		this.intervalList = intervalList;
		this.filterList = filterList;
		this.bot = bot;
	}
}
