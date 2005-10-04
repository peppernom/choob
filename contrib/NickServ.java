import org.uwcs.choob.*;
import org.uwcs.choob.modules.*;
import org.uwcs.choob.support.*;
import org.uwcs.choob.support.events.*;
import java.util.*;
import java.security.*;

/**
 * Choob nickserv checker
 * 
 * @author bucko
 * 
 * Anyone who needs further docs for this module has some serious Java issues.
 * :)
 */

// Holds the NickServ result
public class ResultObj
{
	int result;
	long time;
}

public class NickServ
{
	private static int TIMEOUT = 10000; // Timeout on nick checks.
//	private static int CACHE_TIMEOUT = 3600000; // Timeout on nick check cache (1 hour).
	// ^^ Can only be used once we can verify a user is in a channel and thus trust their online-ness.
	private static int CACHE_TIMEOUT = 300000; // Timeout on nick check cache (5 mins).

	private Map<String,ResultObj> nickChecks;
	Modules mods;
	IRCInterface irc;

	public NickServ(Modules mods, IRCInterface irc)
	{
		nickChecks = new HashMap<String,ResultObj>();
		this.irc = irc;
		this.mods = mods;
	}

	public void destroy(Modules mods)
	{
		synchronized(nickChecks)
		{
			Iterator<String> nicks = nickChecks.keySet().iterator();
			while(nicks.hasNext()) {
				ResultObj result = getNickCheck(nicks.next());
				synchronized(result)
				{
					result.notifyAll();
				}
			}
		}
	}

	public void commandCheck( Message mes ) throws ChoobException
	{
		String nick = mods.util.getParamString( mes );
		int check1 = (Integer)mods.plugin.callAPI("NickServ", "Status", nick);
		if ( check1 > 1 )
		{
			irc.sendContextReply(mes, nick + " is authed (" + check1 + ")!");
		}
		else
		{
			irc.sendContextReply(mes, nick + " is not authed (" + check1 + ")!");
		}
	}

	public int apiStatus( String nick )
	{
		System.out.println("Called by: " + mods.security.getCallerPluginName());
		List<String> names = mods.security.getPluginNames();
		System.out.println("Stack:");
		for(String name: names)
			System.out.println("\t" + name);
		System.out.println("End of stack.");
		ResultObj result = getCachedNickCheck( nick.toLowerCase() );
		if (result != null)
		{
			return result.result;
		}

		result = getNewNickCheck( nick.toLowerCase() );

		synchronized(result)
		{
			try
			{
				result.wait(30000); // Make sure if NickServ breaks we're not screwed
			}
			catch (InterruptedException e)
			{
				// Ooops, timeout
				return -1;
			}
		}
		int status = result.result;
		return status;
	}

	public boolean apiCheck( String nick )
	{
		return apiStatus( nick ) >= 3; // Ie, authed by password
	}

	private ResultObj getNewNickCheck( final String nick )
	{
		System.out.println("Asked for new nick check for " + nick);
		ResultObj result;
		synchronized(nickChecks)
		{
			result = nickChecks.get( nick );
			if ( result == null )
			{
				// Not already waiting on this one
				result = new ResultObj();
				result.result = -1;
				AccessController.doPrivileged( new PrivilegedAction() {
					public Object run()
					{
						irc.sendMessage("NickServ", "STATUS " + nick);
						return null;
					}
				});
				nickChecks.put( nick, result );
			}
		}
		return result;
	}

	private ResultObj getNickCheck( String nick )
	{
		synchronized(nickChecks)
		{
			return (ResultObj)nickChecks.get( nick );
		}
	}

	private ResultObj getCachedNickCheck( String nick )
	{
		ResultObj result;
		synchronized(nickChecks)
		{
			result = (ResultObj)nickChecks.get( nick );
			System.out.println("Cached value for " + nick + " is: " + result);
			if ( result == null )
				// !!! This should never really happen
				return null;

			if (result.result == -1)
				return null;

			if ( result.time + TIMEOUT < System.currentTimeMillis() )
			{
				// expired!
				// TODO - do this in an interval...
				nickChecks.remove( nick );
				System.out.println("Ooops, it expired! It has " + result.time + " vs our " + System.currentTimeMillis() + ".");
				return null;
			}
		}
		return result;
	}

	public void onPrivateNotice( Message mes )
	{
		if ( ! (mes instanceof PrivateNotice) )
			return; // Only interested in private notices

		if ( ! mes.getNick().toLowerCase().equals( "nickserv" ) )
			return; // Not from NickServ --> also don't care

		List params = mods.util.getParams( mes );

		if ( ! ((String)params.get(0)).toLowerCase().equals("status") )
			return; // Wrong type of message!

		String nick = (String)params.get(1);
		int status = Integer.valueOf((String)params.get(2));

		ResultObj result = getNickCheck( nick.toLowerCase() );
		if ( result == null )
			return; // XXX

		synchronized(result)
		{
			result.result = status;
			result.time = System.currentTimeMillis();

			result.notifyAll();
		}
	}

	// Expire old checks when appropriate...

	public void onNickChange( NickChange nc )
	{
		synchronized(nickChecks)
		{
			nickChecks.remove(nc.getNick());
			nickChecks.remove(nc.getNewNick());
		}
	}

	public void onJoin( ChannelJoin cj )
	{
		synchronized(nickChecks)
		{
			nickChecks.remove(cj.getNick());
		}
	}

	public void onKick( ChannelKick ck )
	{
		synchronized(nickChecks)
		{
			nickChecks.remove(ck.getTarget());
		}
	}

	public void onPart( ChannelPart cp )
	{
		synchronized(nickChecks)
		{
			nickChecks.remove(cp.getNick());
		}
	}

	public void onQuit( QuitEvent qe )
	{
		synchronized(nickChecks)
		{
			nickChecks.remove(qe.getNick());
		}
	}
}
