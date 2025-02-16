/** @author Faux */

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

import uk.co.uwcs.choob.modules.Modules;
import uk.co.uwcs.choob.support.IRCInterface;
import uk.co.uwcs.choob.support.events.Message;

public class See
{

	final SimpleDateFormat sdfa = new SimpleDateFormat("haa ");
	final SimpleDateFormat sdfb = new SimpleDateFormat("EEEE");

	private final Modules mods;
	private final IRCInterface irc;

	public See(final IRCInterface irc, final Modules mods)
	{
		this.mods = mods;
		this.irc = irc;
	}

	String timeStamp(final Timestamp d)
	{
		return mods.date.timeStamp(new java.util.Date().getTime() - d.getTime(), false, 3,
				uk.co.uwcs.choob.modules.DateModule.TimeUnit.MINUTE);
	}

	private final synchronized ResultSet getDataFor(final String nick, final Connection conn)
			throws SQLException
	{
		return getDataFor(nick, conn, 5);
	}

	private final synchronized ResultSet getDataFor(final String nick, final Connection conn,
			final int days) throws SQLException
	{
		final Statement stat = conn.createStatement();

		stat.execute("DROP TEMPORARY TABLE IF EXISTS `tempt1`, `tempt2`; ");

		{
			final PreparedStatement s = conn
					.prepareStatement("CREATE TEMPORARY TABLE `tempt1` AS SELECT `Time` FROM `History` WHERE `Time` > "
							+ (System.currentTimeMillis() - 1000 * 60 * 60 * 24 * days)
							+ " AND (CASE INSTR(`Nick`,'|') WHEN 0 THEN `Nick` ELSE LEFT(`Nick`, INSTR(`Nick`,'|')-1) END)=? AND `Channel`IS NOT NULL ORDER BY `Time`; ");
			s.setString(1, nick);
			s.executeUpdate();
		}

		stat
				.execute("ALTER TABLE `tempt1` ADD `index` INT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST; ");

		stat.execute("CREATE TEMPORARY TABLE `tempt2` as SELECT * from `tempt1`; ");
		stat.execute("UPDATE `tempt2` SET `index`:=`index`+1; ");
		stat.execute("ALTER TABLE `tempt2` ADD PRIMARY KEY ( `index` ); ");

		return conn
				.prepareStatement(
						"SELECT DATE_ADD(FROM_UNIXTIME( `tempt1`.`Time` /1000 ), INTERVAL ((`tempt2`.`Time` - `tempt1`.`Time` ) /1000) SECOND) as `start`, FROM_UNIXTIME( `tempt1`.`Time` /1000 ) AS `end`, ((`tempt1`.`Time` - `tempt2`.`Time` ) /1000 /3600) AS `diff` FROM `tempt2` INNER JOIN `tempt1` ON `tempt2`.`index` = `tempt1`.`index` HAVING `diff` > 6;")
				.executeQuery();
	}

	public final synchronized String commandBodyClock(final String mes) throws SQLException
	{
		final String nick = mods.nick.getBestPrimaryNick(mes.trim());

		final Connection conn = mods.odb.getConnection();

		try
		{
			final ResultSet rs = getDataFor(nick, conn);

			String ret = "";

			if (!rs.last())
				return "I don't have enough information to work out the bodyclock for " + nick + ".";
			final Timestamp gotup = rs.getTimestamp("end");
			final long diff = rs.getTimestamp("end").getTime() - rs.getTimestamp("start").getTime();

			float bodyclock = 8.0f + (new java.util.Date().getTime() - gotup.getTime())
					/ (1000.0f * 60.0f * 60.0f);

			long minutes = Math.round((bodyclock - Math.floor(bodyclock)) * 60.0f);

			if (minutes == 60)
			{
				minutes = 0;
				bodyclock++;
			}

			final int cur_hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
			final int hour = (int) Math.floor(bodyclock) % 24;
			final int thingie = (((cur_hour - hour + 36) % 24) - 12) * -3600000;
			final String[] candidateZones = TimeZone.getAvailableIDs(thingie);
			String following = "";
			if (candidateZones.length > 0)
			{
				following = " and implies that he or she is following "
						+ TimeZone.getTimeZone(candidateZones[0]).getDisplayName()
						+ " and may be located in "
						+ candidateZones[new Random().nextInt(candidateZones.length)];
			}
			else
				following = ", and I have /no idea/ where " + thingie + " represents";
			ret += nick
					+ " probably got up "
					+ timeStamp(gotup)
					+ " ago after "
					+ mods.date.timeStamp(diff, false, 2,
							uk.co.uwcs.choob.modules.DateModule.TimeUnit.HOUR)
					+ " of sleep, making his or her body-clock time about " + hour + ":"
					+ (minutes < 10 ? "0" : "") + minutes + following;

			return ret + ".";
		}
		finally
		{
			mods.odb.freeConnection(conn);
		}
	}

	private Date firstSeen(Connection conn, String what) throws SQLException
	{
		final PreparedStatement s = conn
				.prepareStatement("select Time from History where Nick like ? order by Time asc limit 1");
		try
		{
			s.setString(1, what);
			ResultSet r = s.executeQuery();
			if (!r.next())
				return null;
			try
			{
				return new Date(r.getLong(1));
			}
			finally
			{
				r.close();
			}
		}
		finally
		{
			s.close();
		}

	}

	private String format(Date d)
	{
		return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL,
				java.text.DateFormat.FULL).format(d);
	}

	public String commandFirstSeen(final String mes) throws SQLException
	{
		final String nick = mods.nick.getBestPrimaryNick(mes.trim());
		final Connection conn = mods.odb.getConnection();
		try
		{
			final Date rel = firstSeen(conn, nick + "%");
			if (rel == null)
				return "Never seen " + nick + ".";
			return nick + " was first seen on " + format(rel) + ", "
				+ mods.date.timeLongStamp(new java.util.Date().getTime() - rel.getTime(), 2)
				+ " ago.";
		}
		finally
		{
			mods.odb.freeConnection(conn);
		}
	}

	/* This Whores like hell, fix plx */
	// *
	public String commandFreshers(String mes) throws SQLException
	{
		final Connection conn = mods.odb.getConnection();
		Set<String> people = new TreeSet<String>();
		Calendar testdate = Calendar.getInstance();

		if (testdate.get(Calendar.MONTH) < 7)
			testdate.set(testdate.get(Calendar.YEAR) - 1, 7, 1);
		else
			testdate.set(testdate.get(Calendar.YEAR), 7, 1);

		try
		{
			final PreparedStatement s = conn
					.prepareStatement("select distinct Nick from History where Time > ? group by Nick having count(Time) > 1");
			try
			{
				s.setLong(1, testdate.getTime().getTime());
				ResultSet r = s.executeQuery();
				try
				{
					while (r.next())
					{
						people.add(mods.nick.getBestPrimaryNick(r.getString(1)));
					}
				}
				finally
				{
					r.close();
				}
			}
			finally
			{
				s.close();
			}

			// people is now a list of people who spoke this academic year
			String res = "Freshers: ";
			for (String pe : people)
			{
				final Date firstSeen = firstSeen(conn, pe + "%");
				if (firstSeen != null && firstSeen.after(testdate.getTime()))
					res += pe + " ";
			}
			return res;
		}
		finally
		{
			mods.odb.freeConnection(conn);
		}
	}

	// */
	private final String datelet(final Date d)
	{
		return sdfa.format(d).toLowerCase() + sdfb.format(d);
	}

	public final synchronized String commandPattern(final String mes) throws SQLException
	{
		final String nick = mods.nick.getBestPrimaryNick(mes.trim());

		final Connection conn = mods.odb.getConnection();

		try
		{
			final ResultSet rs = getDataFor(nick, conn);

			if (!rs.first())
				return "I don't have enough information about " + nick + ".";

			rs.beforeFirst();

			String ret = nick + " was sleeping: ";
			while (rs.next())
			{
				final Date start = new Date(rs.getTimestamp("start").getTime());
				final Date end = new Date(rs.getTimestamp("end").getTime());
				ret += datelet(start) + " -> " + datelet(end) + ", ";
			}

			if (ret.length() > 2)
				ret = ret.substring(0, ret.length() - 2);
			return ret + ".";
		}
		finally
		{
			mods.odb.freeConnection(conn);
		}
	}

	public synchronized void webDump(final PrintWriter out, final String args, final String[] from)
	{
		try
		{
			out.println("HTTP/1.0 200 OK");
			out.println("Content-Type: text/plain");
			out.println();

			{
				final String nick = args;

				final Connection conn = mods.odb.getConnection();

				final ResultSet rs = getDataFor(nick, conn, 21);

				if (!rs.first())
					return;
				rs.beforeFirst();

				while (rs.next())
				{
					final Date start = new Date(rs.getTimestamp("start").getTime());
					final Date end = new Date(rs.getTimestamp("end").getTime());
					out.println(start.getTime() + " " + end.getTime());
				}

				mods.odb.freeConnection(conn);
			}
		}
		catch (final Throwable t)
		{
			out.println("ERROR!");
			t.printStackTrace();
		}
	}

	// * LA LA LA REMMED OUT AND INVISIBLE
	public void commandMidday(final Message mes) throws SQLException
	{
		final Connection conn = mods.odb.getConnection();
		try
		{
			String nick = mods.util.getParamString(mes).trim();
			String message;

			float t;
			if (nick.equals(""))
			{
				float rt = 0;
				final Set<String> nickset = new HashSet<String>();
				for (final String n : irc.getUsers(mes.getContext()))
					nickset.add(mods.nick.getBestPrimaryNick(n));
				final String[] nicks = nickset.toArray(new String[0]);

				int succ = 0, fail = 0;
				for (final String n : nicks)
				{
					try
					{
						final float md = midday(n, conn);
						System.out.println(n + "\t" + md);
						rt += md;

						++succ;
					}
					catch (final RuntimeException e)
					{
						++fail;
					}
				}

				if (succ < 2)
				{
					irc.sendContextReply(mes, "Not enough users in " + mes.getContext() + ".");
					return;
				}
				message = "From " + succ + " users in " + mes.getContext() + ", the average";

				t = rt / succ;
			}
			else
			{
				t = midday(nick = mods.nick.getBestPrimaryNick(nick), conn);
				message = nick + "'s";
			}
			final int qnr = (int) ((t - (int) t) * 60);
			irc.sendContextReply(mes, message + " midday is " + (int) t + ":"
					+ (qnr < 10 ? "0" : "") + qnr + ".");
		}
		finally
		{
			mods.odb.freeConnection(conn);
		}

	}

	public String[] helpCommandBang = {
		"Finds other people in the channel with similar sleeping patterns.",
		"<Nick>",
		"<Nick> is the person to compare with"
	};

	public void commandBang(final Message mes) throws SQLException
	{
		final float diff = 1f;

		final Connection conn = mods.odb.getConnection();
		try
		{
			String nick = mods.util.getParamString(mes).trim();

			float midday;
			int fail = 0;
			if (nick.equals(""))
			{
				irc.sendContextReply(mes, "You must specify a nick to bang" + mes.getContext() + ".");
			}
			else
			{
				midday = midday(nick = mods.nick.getBestPrimaryNick(nick), conn);
				final Set<String> nicks = new HashSet<String>();
				for (final String n: irc.getUsers(mes.getContext())) {
					final String userNick = mods.nick.getBestPrimaryNick(n);
					try {
						if(Math.abs(midday(userNick, conn)-midday) < diff) {
							nicks.add(userNick);
						}
					} catch (RuntimeException e) {
						fail++;
					}
				}
				String acc = "";
				for(String n: nicks)
					acc += ", "+n;
				irc.sendContextReply(mes, acc.substring(2));
				irc.sendContextReply(mes, fail+" people are too unreliable to bang");
			}
		}
		finally
		{
			mods.odb.freeConnection(conn);
		}

	}

	private float midday(String nick, final Connection conn) throws SQLException
	{
		// if they wern't awake this long, it doesn't count.
		final int minday = 7 * 60 * 60 * 1000;

		nick = mods.nick.getBestPrimaryNick(nick);

		final ResultSet rs = getDataFor(nick, conn);

		int c = 0;
		float midday = 0;

		if (!rs.first())
			throw new RuntimeException("No data for " + nick + ". Cannot continue.");
		rs.beforeFirst();
		Date lastEnd = null;

		while (rs.next())
		{
			final Date start = new Date(rs.getTimestamp("start").getTime());
			final Date end = new Date(rs.getTimestamp("end").getTime());

			if (lastEnd != null)
			{
				final long foo = -(lastEnd.getTime() - start.getTime());
				if (foo > minday)
				{
					final Calendar cal = new GregorianCalendar();
					cal.setTime(new Date(end.getTime() + foo * (12 - 8) / (24 - 8)));
					midday += cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0;
					c++;
				}
			}
			lastEnd = start;
		}

		if (c == 0)
			throw new RuntimeException(nick + midday);
		return midday / c;
	}
	// */
}
