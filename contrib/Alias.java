import org.uwcs.choob.*;
import org.uwcs.choob.modules.*;
import org.uwcs.choob.support.*;
import org.uwcs.choob.support.events.*;
import java.util.*;
import java.util.regex.*;

public class AliasObject
{
	public AliasObject(String name, String converted, String owner)
	{
		this.name = name;
		this.converted = converted;
		this.owner = owner;
		this.locked = false;
		this.id = 0;
	}
	public AliasObject() {}
	public int id;
	public String name;
	public String converted;
	public String owner;
	public boolean locked;
}

public class Alias
{
	private final String validator="[^A-Za-z_0-9]+";

	private Modules mods;
	private IRCInterface irc;
	public Alias(Modules mods, IRCInterface irc)
	{
		this.mods = mods;
		this.irc = irc;
	}

	public String[] helpSyntax = {
		  "Aliases use a clever alias syntax: You can either just give a"
		+ " simple string with no '$' characters, in which case any user"
		+ " parameters will be appended to the command. Alternatively, you can"
		+ " give a string with '$' characters, in which case, '$i' is replaced"
		+ " with parameter #i, '$$' is replaced simply with '$', '$[i-j]' is"
		+ " replaced with parameters #i through #j with '$[i-]' and '$[-j]'"
		+ " defined in the obvious way, and '$.' is replaced with nothing.",
		  "Example: If 'Foo' is aliased to 'Bar.Baz', 'Foo 1 2 3' will become"
		+ " 'Bar.Baz 1 2 3'. If 'Foo' is aliased to 'Bar.Baz$.', 'Foo 1 2 3'"
		+ " will become 'Bar.Baz', and if 'Foo' is aliased to"
		+ " 'Bar.Baz $3 $[1-2]', 'Foo 1 2 3' will become 'Bar.Baz 3 1 2'."
	};

	public String[] helpCommandAdd = {
		"Add an alias to the bot.",
		"<Name> <Alias>",
		"<Name> is the name of the alias to add",
		"<Alias> is the alias content. See Alias.Syntax"
	};
	public void commandAdd( Message mes ) throws ChoobException
	{
		List<String> params = mods.util.getParams(mes, 2);

		if (params.size() <= 2)
		{
			irc.sendContextReply(mes, "Syntax: Alias.Add <Name> <Alias>");
			return;
		}

		String name = params.get(1).replaceAll(validator, "").toLowerCase();
		String conv = params.get(2);

		AliasObject alias = getAlias(name);

		String nick = mods.security.getRootUser(mes.getNick());

		String oldAlias = ""; // Set to content of old alias, if there was one.
		if (alias != null)
		{
			if (alias.locked)
			{
				if (alias.owner.toLowerCase().equals(nick.toLowerCase()))
					mods.security.checkNS(mes.getNick());
				else
					mods.security.checkNickPerm(new ChoobPermission("plugins.alias.unlock"), mes.getNick());
			}

			oldAlias = " (was " + alias.converted + ")";

			alias.converted = conv;
			alias.owner = nick;

			mods.odb.update(alias);
		}
		else
			mods.odb.save(new AliasObject(name, conv, nick));

		irc.sendContextReply(mes, "Aliased '" + name + "' to '" + conv + "'" + oldAlias + ".");
	}

	public void commandRemove( Message mes ) throws ChoobException
	{
		List<String> params = mods.util.getParams(mes, 2);

		if (params.size() <= 1)
		{
			irc.sendContextReply(mes, "Syntax: Alias.Remove <aliasname>");
			return;
		}

		String name = params.get(1).replaceAll(validator, "").toLowerCase();
		String conv = params.get(2);

		AliasObject alias = getAlias(name);

		String nick = mods.security.getRootUser(mes.getNick());

		String oldAlias = ""; // Set to content of old alias, if there was one.
		if (alias != null)
		{
			if (alias.locked)
			{
				if (alias.owner.toLowerCase().equals(nick.toLowerCase()))
					mods.security.checkNS(mes.getNick());
				else
					mods.security.checkNickPerm(new ChoobPermission("plugins.alias.unlock"), mes.getNick());
			}

			oldAlias = " (was " + alias.converted + ")";

			mods.odb.delete(alias);
			
			irc.sendContextReply(mes, "Deleted '" + alias.name + "', was aliased to '" + alias.converted + "'.");
		}
		else
			irc.sendContextReply(mes, "Alias not found.");
	}

	public void commandList( Message mes ) throws ChoobException
	{
		String clause = "locked = 0";

		String parm = mods.util.getParamString(mes).toLowerCase();
		if (parm.equals("locked"))
			clause = "locked = 1";
		else if (parm.equals("all"))
			clause = "1";

		List<AliasObject> results = mods.odb.retrieve( AliasObject.class, "WHERE " + clause );

		if (results.size() == 0)
			irc.sendContextReply(mes, "No aliases.");
		else
		{
			String list = "Alias list: ";
			for (int i=0; i < results.size(); i++)
			{
				list += results.get(i).name;
				if (i < results.size() - 2)
					list += ", ";
				else if (i == results.size() - 2)
				{
					if (i == 0)
						list += " and ";
					else
						list += ", and ";
				}
			}
			list += ".";
			irc.sendContextReply(mes, list);
		}
	}

	public void commandShow( Message mes ) throws ChoobException
	{
		List<String> params = mods.util.getParams(mes, 2);

		if (params.size() < 2)
		{
			irc.sendContextReply(mes, "Please specify the name of the alias to show.");
			return;
		}

		String name = params.get(1).replaceAll(validator,"").toLowerCase();

		AliasObject alias = getAlias(name);

		if (alias == null)
			irc.sendContextReply(mes, "Alias not found.");
		else
			irc.sendContextReply(mes, "'" + alias.name + "'" + (alias.locked ? "[LOCKED]" : "") + " was aliased to '" + alias.converted + "' by '" + alias.owner + "'.");
	}

	public void commandLock( Message mes ) throws ChoobException
	{
		List<String> params = mods.util.getParams(mes, 2);

		if (params.size() < 2)
		{
			irc.sendContextReply(mes, "Please specify the name of the alias to lock.");
			return;
		}

		String name = params.get(1).replaceAll(validator,"").toLowerCase();

		AliasObject alias = getAlias(name);

		if (alias != null)
		{
			// No need to NS check here.
			alias.locked = true;
			mods.odb.update(alias);
			irc.sendContextReply(mes, "Locked " + name + "!");
		}
		else
		{
			irc.sendContextReply(mes, "Alias " + name + " not found.");
		}
	}

	public void commandUnLock( Message mes ) throws ChoobException
	{
		List<String> params = mods.util.getParams(mes, 2);

		if (params.size() < 2)
		{
			irc.sendContextReply(mes, "Please specify the name of the alias to unlock.");
			return;
		}

		String name = params.get(1).replaceAll(validator,"").toLowerCase();

		AliasObject alias = getAlias(name);

		if (alias != null)
		{
			String nick = mods.security.getRootUser(mes.getNick());

			if (nick.toLowerCase().equals(alias.owner.toLowerCase()))
				mods.security.checkNS(mes.getNick());
			else
				mods.security.checkNickPerm(new ChoobPermission("plugins.alias.unlock"), mes.getNick());

			alias.locked = false;
			mods.odb.update(alias);
			irc.sendContextReply(mes, "Unlocked " + name + "!");
		}
		else
		{
			irc.sendContextReply(mes, "Alias " + name + " not found.");
		}
	}

	private AliasObject getAlias( String name ) throws ChoobException
	{
		String alias = name.replaceAll(validator,"").toLowerCase();

		List<AliasObject> results = mods.odb.retrieve( AliasObject.class, "WHERE name='" + alias + "'" );

		if (results.size() == 0)
			return null;
		else
			return results.get(0);
	}

	public void onPrivateMessage( Message mes ) throws ChoobException
	{
		onMessage( mes );
	}

	// Muhahahahahahahahaha --bucko
	public void onMessage( Message mes ) throws ChoobException
	{
		String text = mes.getMessage();

		Matcher matcher = Pattern.compile(irc.getTriggerRegex()).matcher(text);
		int offset = 0;

		// Make sure this is actually a command...
		if (matcher.find())
		{
			offset = matcher.end();
		}
		else if (!(mes instanceof PrivateEvent))
		{
			return;
		}

		// Stop recursion
		if (mes.getSynthLevel() > 1) {
			irc.sendContextReply(mes, "Synthetic event recursion detected. Stopping.");
			return;
		}

		// Text is everything up to the next space...
		int cmdEnd = text.indexOf(' ', offset);
		String cmdParams;
		if (cmdEnd == -1)
		{
			cmdEnd = text.length();
			cmdParams = "";
		}
		else
		{
			cmdParams = text.substring(cmdEnd);
		}

		int dotIndex = text.indexOf('.', offset);
		// Real command, not an alias...
		if (dotIndex != -1 && dotIndex < cmdEnd)
			return;

		String command = text.substring(offset, cmdEnd);
		AliasObject alias = getAlias( command );

		if (alias == null)
		{
			// Consider an error here...
			return;
		}

		String converted = alias.converted;
		if (converted.indexOf("$") == -1)
			// Simple syntax
			command = irc.getTrigger() + converted + cmdParams;
		else
		{
			// Advanced syntax
			List<String> paramList = mods.util.getParams(mes);
			String[] params = new String[paramList.size()];
			params = paramList.toArray(params);
			StringBuilder newCom = new StringBuilder(irc.getTrigger());

			int pos = converted.indexOf('$'), oldPos = 0;
			int convEnd = converted.length() - 1;
			while (pos != -1)
			{
				newCom.append(converted.substring(oldPos, pos));

				// Sanity check for $ at end of alias...
				if (pos == convEnd)
				{
					newCom.append("$");
					break;
				}

				char next = converted.charAt(pos + 1);
				System.out.println("Found: $" + next);
				if (next == '$')
				{
					newCom.append("$");
					pos = pos + 2;
				}
				else if (next == '.')
				{
					pos = pos + 2;
				}
				else if (next == '*')
				{
					for(int i = 1; i < params.length; i++)
					{
						newCom.append(params[i]);
						if (i != params.length - 1)
							newCom.append(" ");
					}
					pos = pos + 2;
				}
				else if (next >= '0' && next <= '9')
				{
					int end = pos + 1;
					while(true)
					{
						if (end > convEnd)
							break;
						char test = converted.charAt(end);
						if (test < '0' || test > '9')
							break;
						// Another number!
						end++;
					}
					int paramNo = 0;
					try
					{
						paramNo = Integer.parseInt(converted.substring(pos + 1, end));
					}
					catch (NumberFormatException e)
					{
						// LIES!
					}
					System.out.println("Numeric: " + paramNo);
					if (paramNo < params.length)
						newCom.append(params[paramNo]);
					pos = end;
				}
				else if (next == '[')
				{
					if (converted.length() < pos + 3)
						break;
					int firstParam = -1, lastParam = -1;
					int newPos = pos + 2;
					char test = converted.charAt(newPos);

					// First param is '-' - set firstParam to be undefined.
					if (test == '-')
					{
						firstParam = -2;
						newPos++;
						test = converted.charAt(newPos);
					}

					// Begin eating params.
					if (test >= '0' && test <= '9')
					{
						int end = newPos + 1;
						while(true)
						{
							if (end > convEnd)
								break;
							test = converted.charAt(end);

							// End of number!
							if (test == '-' || test == ']')
							{
								System.out.println("Breaking on " + test);
								int paramNo = -1;
								try
								{
									paramNo = Integer.parseInt(converted.substring(newPos, end));
								}
								catch (NumberFormatException e)
								{
									// LIES!
								}
								newPos = end + 1;
								if (firstParam == -1)
								{
									if (test == ']')
										break;
									firstParam = paramNo;
								}
								else if (lastParam == -1)
								{
									if (test == '-')
										break;
									lastParam = paramNo;
									break;
								}
							}
							else if (test < '0' || test > '9')
								break;
							// Another number!
							end++;
						}

						// Sort out undefined length ranges
						if (firstParam == -2)
						{
							// lastParam > 0
							if (lastParam <= 1)
								firstParam = params.length - 1;
							else
								firstParam = 1;
						}
						else if (lastParam == -1)
						{
							lastParam = params.length - 1;
						}

						// Process output now.
						if (lastParam < 0 || firstParam < 0 || lastParam + firstParam > 100)
						{
							newCom.append("$");
							pos++;
						}
						else
						{
							int direction = lastParam > firstParam ? 1 : -1;
							lastParam += direction; // For simpler termination of loop.
							for(int i = firstParam; i != lastParam; i += direction)
							{
								if (i < params.length)
								{
									newCom.append(params[i]);
									if (i != lastParam - direction)
										newCom.append(" ");
								}
							}
							pos = end + 1;
						}
					}
					else
					{
						newCom.append("$");
						pos++;
					}
				}
				else
				{
					newCom.append("$");
					pos++;
				}
				oldPos = pos;
				pos = converted.indexOf('$', pos);
			}
			newCom.append(converted.substring(oldPos, convEnd + 1));
			command = newCom.toString();
		}

		Message newMes = (Message)mes.cloneEvent( command );

		System.out.println("Converted " + text + " -> " + command);

		mods.synthetic.doSyntheticMessage( newMes );
	}
}
