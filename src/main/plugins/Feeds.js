// JavaScript plugin for RSS and Atom feeds.
// 
// Copyright 2005 - 2006, James G. Ross
// 

var BufferedReader    = Packages.java.io.BufferedReader;
var File              = Packages.java.io.File;
var FileInputStream   = Packages.java.io.FileInputStream;
var InputStreamReader = Packages.java.io.InputStreamReader;
var System            = Packages.java.lang.System;
var URL               = Packages.java.net.URL;
var ChoobPermission   = Packages.uk.co.uwcs.choob.support.ChoobPermission;
var GetContentsCached = Packages.uk.co.uwcs.choob.support.GetContentsCached;


function log(msg) {
	dumpln("FEEDS [" + (new Date()) + "] " + msg);
}

String.prototype.trim =
function _trim() {
	return this.replace(/^\s+/, "").replace(/\s+$/, "");
}

// Constructor: Feeds
function Feeds(mods, irc) {
	profile.start();
	this._mods = mods;
	this._irc = irc;
	this._debugStatus = "";
	this._debugChannel    = "#testing42";
	this._announceChannel = "#testing42";
	this._debug_profile    = false;
	this._debug_interval   = false;
	this._debug_store      = false;
	this._debug_xml        = false;
	this._debug_trace      = false;
	
	this._feedList = new Array();
	this._feedCheckLock = false;
	
	var feeds = mods.odb.retrieve(Feed, "");
	for (var i = 0; i < feeds.size(); i++) {
		var feed = feeds.get(i);
		
		// Allow the feed to save itself when it makes changes.
		feed.__mods = mods;
		feed.save = function _feed_save() { this.__mods.odb.update(this) };
		
		feed.init(this, "");
		this._feedList.push(feed);
	}
	
	mods.interval.callBack("feed-check", 10000, 1);
	profile.stop("init");
	this._setStatus("Waiting for first feed check.");
}


Feeds.prototype.info = [
		"Generic feed reader with notification.",
		"James Ross",
		"silver@warwickcompsoc.co.uk",
		"$Rev$$Date$"
	];


// Callback for all intervals from this plugin.
Feeds.prototype.interval = function(param, mods, irc) {
	function mapCase(s, a, b) {
		return a.toUpperCase() + b.toLowerCase();
	};
	
	if (param) {
		var name = "_" + param.replace(/-(\w)(\w+)/g, mapCase) + "Interval";
		
		if (name in this) {
			try {
				this[name](param, mods, irc);
			} catch(ex) {
				log("Exception in " + name + ": " + ex + (ex.fileName ? " at <" + ex.fileName + ":" + ex.lineNumber + ">":""));
				irc.sendMessage(this._debugChannel, "Exception in " + name + ": " + ex + (ex.fileName ? " at <" + ex.fileName + ":" + ex.lineNumber + ">":""));
			}
		} else {
			irc.sendMessage(this._debugChannel, "Interval code missing: " + name);
		}
		
	} else {
		irc.sendMessage(this._debugChannel, "Unnamed interval attempted!");
	}
}


// Command: Add
Feeds.prototype.commandAdd = function(mes, mods, irc) {
	var params = mods.util.getParams(mes, 2);
	if (params.size() <= 2) {
		irc.sendContextReply(mes, "Syntax: Feeds.Add <feedname> <url>");
		return;
	}
	var feedName = String(params.get(1)).trim();
	var feedURL  = String(params.get(2)).trim();
	
	// Remove existing feed, if possible.
	var feed = this._getFeed(feedName);
	if (feed) {
		if (!this._canAdminFeed(feed, mes)) {
			irc.sendContextReply(mes, "You don't have permission to replace feed '" + feedName + "'!");
		}
		this._removeFeed(feed);
	}
	
	// Load new feed.
	irc.sendContextReply(mes, "Loading feed '" + feedName + "'...");
	var feed = new Feed(this, feedName, feedURL, mes.getContext());
	mods.odb.save(feed);
	
	// Allow the feed to save itself when it makes changes.
	feed.__mods = mods;
	feed.save = function _feed_save() { this.__mods.odb.update(this) };
	
	feed.owner = this._getOwnerFrom(mes.getNick());
	this._feedList.push(feed);
	feed.addOutputTo(mes.getContext());
	
	// Check feed now.
	mods.interval.callBack("feed-check", 1000, 1);
}
Feeds.prototype.commandAdd.help = [
		"Adds a new feed.",
		"<feedname> <url>",
		"<feedname> is the name for the new feed",
		"<url> is the URL of the new feed"
	];


// Command: Remove
Feeds.prototype.commandRemove = function(mes, mods, irc) {
	var params = mods.util.getParams(mes, 1);
	if (params.size() <= 1) {
		irc.sendContextReply(mes, "Syntax: Feeds.Remove <feedname>");
		return;
	}
	var feedName = String(params.get(1)).trim();
	
	var feed = this._getFeed(feedName);
	if (!feed) {
		irc.sendContextReply(mes, "Feed '" + feedName + "' not found.");
		return;
	}
	if (!this._canAdminFeed(feed, mes)) {
		irc.sendContextReply(mes, "You don't have permission to remove feed '" + feedName + "'!");
		return;
	}
	
	this._removeFeed(feed);
	irc.sendContextReply(mes, "Feed " + feed.getDisplayName() + " removed.");
}
Feeds.prototype.commandRemove.help = [
		"Removes an feed entirely.",
		"<feedname>",
		"<feedname> is the name of the feed to remove"
	];


// Command: List
Feeds.prototype.commandList = function(mes, mods, irc) {
	if (this._feedList.length == 0) {
		irc.sendContextReply(mes, "No feeds are set up.");
		return;
	}
	
	var params = mods.util.getParams(mes, 1);
	if (params.size() > 1) {
		var findName = String(params.get(1)).trim();
		var findIO = "," + findName.toLowerCase() + ",";
		var feedsIO = new Array();
		var feeds = new Array();
		
		for (var i = 0; i < this._feedList.length; i++) {
			// Skip private feeds that user can't control.
			if (this._feedList[i].isPrivate && !this._canAdminFeed(this._feedList[i], mes)) {
				continue;
			}
			
			if (("," + (this._feedList[i]._outputTo.join(",") || "*nowhere*") + ",").toLowerCase().indexOf(findIO) != -1) {
				var error = this._feedList[i].getError();
				feedsIO.push(this._feedList[i].getDisplayName() + " (" + (error ? "error" : this._feedList[i]._lastItemCount) + ")");
			} else if (this._feedList[i].name.toLowerCase() == findName.toLowerCase()) {
				var dests = this._feedList[i]._outputTo.join(", ");
				var error = this._feedList[i].getError();
				feeds.push("Feed " + this._feedList[i].getDisplayName()
						+ " owned by " + (this._feedList[i].owner ? this._feedList[i].owner : "<unknown>")
						+ (this._feedList[i].isPrivate ? " (\x02private\x02)" : "") + ", "
						+ (error ?
							(error) :
							(this._feedList[i]._lastItemCount + " items (" + this._feedList[i].getLastLoaded() + ")")
						) + ", "
						+ "TTL of " + this._feedList[i].ttl + "s, source <" + this._feedList[i].url + ">."
						+ (dests ? " Notifications to: " + dests + "." : ""));
			}
		}
		if (feedsIO.length > 0) {
			irc.sendContextReply(mes, "Feeds for " + findName + ": " + feedsIO.sort().join(", ") + ".");
		}
		for (var i = 0; i < feeds.sort().length; i++) {
			irc.sendContextReply(mes, feeds[i]);
		}
		if (feedsIO.length + feeds.length == 0) {
			irc.sendContextReply(mes, "Feed or target '" + findName + "' not found.");
		}
	} else {
		var outputs = new Object();
		var errs = new Array();
		
		for (var i = 0; i < this._feedList.length; i++) {
			// Skip private feeds that user can't control.
			if (this._feedList[i].isPrivate && !this._canAdminFeed(this._feedList[i], mes)) {
				continue;
			}
			
			var st = this._feedList[i].getSendTo();
			
			for (var j = 0; j < st.length; j++) {
				if (!(st[j] in outputs)) {
					outputs[st[j]] = 0;
				}
				outputs[st[j]]++;
			}
			if (st.length == 0) {
				if (!("*nowhere*" in outputs)) {
					outputs["*nowhere*"] = 0;
				}
				outputs["*nowhere*"]++;
			}
			
			if (this._feedList[i].getError()) {
				errs.push(this._feedList[i]);
			}
		}
		
		var outputList = new Array();
		for (var o in outputs) {
			outputList.push(o + " (" + outputs[o]  +")");
		}
		irc.sendContextReply(mes, "Feeds: " + outputList.sort().join(", ") + ".");
		for (var i = 0; i < errs.length; i++) {
			irc.sendContextReply(mes, "Feed " + errs[i].getDisplayName() + ": " + errs[i].getError() + ".");
		}
	}
}
Feeds.prototype.commandList.help = [
		"Lists all feeds and where they are displayed, or information about a single feed.",
		"[<name>]",
		"<name> is the (optional) name of a feed or target (e.g. channel) to get details on"
	];


// Command: AddOutput
Feeds.prototype.commandAddOutput = function(mes, mods, irc) {
	var params = mods.util.getParams(mes, 2);
	if (params.size() <= 2) {
		irc.sendContextReply(mes, "Syntax: Feeds.AddOutput <feedname> <dest>");
		return;
	}
	var feedName = String(params.get(1)).trim();
	
	var feed = this._getFeed(feedName);
	if (!feed) {
		irc.sendContextReply(mes, "Feed '" + feedName + "' not found.");
		return;
	}
	if (!this._canAdminFeed(feed, mes)) {
		irc.sendContextReply(mes, "You don't have permission to edit feed '" + feedName + "'!");
		return;
	}
	var feedDest = String(params.get(2)).trim();
	
	if (feed.addOutputTo(feedDest)) {
		irc.sendContextReply(mes, "Feed " + feed.getDisplayName() + " will now output to '" + feedDest + "'.");
	} else {
		irc.sendContextReply(mes, "Feed " + feed.getDisplayName() + " already outputs to '" + feedDest + "'.");
	}
}
Feeds.prototype.commandAddOutput.help = [
		"Adds a new output destination for an feed.",
		"<feedname> <dest>",
		"<feedname> is the name of the feed to modify",
		"<dest> is the channel name to send notifications to"
	];


// Command: RemoveOutput
Feeds.prototype.commandRemoveOutput = function(mes, mods, irc) {
	var params = mods.util.getParams(mes, 2);
	if (params.size() <= 2) {
		irc.sendContextReply(mes, "Syntax: Feeds.RemoveOutput <feedname> <dest>");
		return;
	}
	var feedName = String(params.get(1)).trim();
	
	var feed = this._getFeed(feedName);
	if (!feed) {
		irc.sendContextReply(mes, "Feed '" + feedName + "' not found.");
		return;
	}
	if (!this._canAdminFeed(feed, mes)) {
		irc.sendContextReply(mes, "You don't have permission to edit feed '" + feedName + "'!");
		return;
	}
	var feedDest = String(params.get(2)).trim();
	
	if (feed.removeOutputTo(feedDest)) {
		irc.sendContextReply(mes, "Feed " + feed.getDisplayName() + " will no longer output to '" + feedDest + "'.");
	} else {
		irc.sendContextReply(mes, "Feed " + feed.getDisplayName() + " doesn't output to '" + feedDest + "'.");
	}
}
Feeds.prototype.commandRemoveOutput.help = [
		"Removes an output destination for an feed.",
		"<feedname> <dest>",
		"<feedname> is the name of the feed to modify",
		"<dest> is the channel name to stop sending notifications to"
	];


// Command: Recent
Feeds.prototype.commandRecent = function(mes, mods, irc) {
	var params = mods.util.getParams(mes, 2);
	if (params.size() <= 1) {
		irc.sendContextReply(mes, "Syntax: Feeds.Recent <feedname> [[<offset>] <count>]");
		return;
	}
	var feedName = String(params.get(1)).trim();
	
	var feed = this._getFeed(feedName);
	if (!feed) {
		irc.sendContextReply(mes, "Feed '" + feedName + "' not found.");
		return;
	}
	// Allow anyone to get recent items for public feeds, and only someone
	// who can admin a feed to do it for private feeds.
	if (feed.isPrivate && !this._canAdminFeed(feed, mes)) {
		irc.sendContextReply(mes, "You don't have permission to view feed '" + feedName + "'!");
		return;
	}
	
	var offset = 0;
	var count  = 5;
	if (params.size() > 3) {
		offset = params.get(2);
		count  = params.get(3);
	} else if (params.size() > 2) {
		count  = params.get(2);
	}
	
	if ((String(offset).trim() != String(Number(offset))) || (offset < 0)) {
		irc.sendContextReply(mes, "<offset> must be numeric and non-negative");
		return;
	}
	
	if ((String(count).trim() != String(Number(count))) || (count <= 0)) {
		irc.sendContextReply(mes, "<count> must be numeric and positive");
		return;
	}
	
	if (offset + count > feed._lastItemCount) {
		count = feed._lastItemCount - offset;
	}
	
	feed.showRecent(mes.getContext(), offset, count);
}
Feeds.prototype.commandRecent.help = [
		"Displays a number of recent items from a feed.",
		"<feedname> [[<offset>] <count>]",
		"<feedname> is the name of the feed to modify",
		"<offset> is now many entires back in time to go (default is 0)",
		"<count> is the number of recent items to show (default is 5)"
	];


// Command: SetOwner
Feeds.prototype.commandSetOwner = function(mes, mods, irc) {
	var params = mods.util.getParams(mes, 2);
	if (params.size() <= 2) {
		irc.sendContextReply(mes, "Syntax: Feeds.SetOwner <feedname> <owner>");
		return;
	}
	var feedName = String(params.get(1)).trim();
	
	var feed = this._getFeed(feedName);
	if (!feed) {
		irc.sendContextReply(mes, "Feed '" + feedName + "' not found.");
		return;
	}
	if (!this._canAdminFeed(feed, mes)) {
		irc.sendContextReply(mes, "You don't have permission to edit feed '" + feedName + "'!");
		return;
	}
	var owner = this._getOwnerFrom(String(params.get(2)).trim());
	
	feed.owner = owner;
	irc.sendContextReply(mes, "Feed " + feed.getDisplayName() + " now has an owner of " + owner + ".");
	mods.odb.update(feed);
}
Feeds.prototype.commandSetOwner.help = [
		"Sets the owner of the feed, who has full control over it.",
		"<feedname> <ttl>",
		"<feedname> is the name of the feed to modify",
		"<owner> is the new owner",
	];


// Command: SetPrivate
Feeds.prototype.commandSetPrivate = function(mes, mods, irc) {
	var params = mods.util.getParams(mes, 2);
	if (params.size() <= 2) {
		irc.sendContextReply(mes, "Syntax: Feeds.SetPrivate <feedname> <value>");
		return;
	}
	var feedName = String(params.get(1)).trim();
	
	var feed = this._getFeed(feedName);
	if (!feed) {
		irc.sendContextReply(mes, "Feed '" + feedName + "' not found.");
		return;
	}
	if (!this._canAdminFeed(feed, mes)) {
		irc.sendContextReply(mes, "You don't have permission to edit feed '" + feedName + "'!");
		return;
	}
	var isPrivate = String(params.get(2)).trim();
	isPrivate = ((isPrivate == "1") || (isPrivate == "on") || (isPrivate == "true") || (isPrivate == "yes"));
	
	feed.isPrivate = isPrivate;
	if (isPrivate) {
		irc.sendContextReply(mes, "Feed " + feed.getDisplayName() + " is now private.");
	} else {
		irc.sendContextReply(mes, "Feed " + feed.getDisplayName() + " is no longer private.");
	}
	mods.odb.update(feed);
}
Feeds.prototype.commandSetOwner.help = [
		"Sets whether the feed shows up to users who can't administrate it.",
		"<feedname> <value>",
		"<feedname> is the name of the feed to modify",
		"<value> is either 'true' or 'false'",
	];


// Command: SetTTL
Feeds.prototype.commandSetTTL = function(mes, mods, irc) {
	var params = mods.util.getParams(mes, 2);
	if (params.size() <= 2) {
		irc.sendContextReply(mes, "Syntax: Feeds.SetTTL <feedname> <ttl>");
		return;
	}
	var feedName = String(params.get(1)).trim();
	
	var feed = this._getFeed(feedName);
	if (!feed) {
		irc.sendContextReply(mes, "Feed '" + feedName + "' not found.");
		return;
	}
	if (!this._canAdminFeed(feed, mes)) {
		irc.sendContextReply(mes, "You don't have permission to edit feed '" + feedName + "'!");
		return;
	}
	var feedTTL = 1 * params.get(2);
	
	if (feedTTL < 60) {
		irc.sendContextReply(mes, "Sorry, but a TTL of less than 60 is not allowed.");
		return;
	}
	
	feed.ttl = feedTTL;
	irc.sendContextReply(mes, "Feed " + feed.getDisplayName() + " now has a TTL of " + feedTTL + ".");
	mods.odb.update(feed);
}
Feeds.prototype.commandSetTTL.help = [
		"Sets the TTL (time between updates) of a feed.",
		"<feedname> <ttl>",
		"<feedname> is the name of the feed to modify",
		"<ttl> is the new TTL for the feed",
	];


// Command: SetDebug
Feeds.prototype.commandSetDebug = function(mes, mods, irc) {
	if (!mods.security.hasPerm(new ChoobPermission("feeds.debug"), mes)) {
		irc.sendContextReply(mes, "You don't have permission to debug Feeds!");
		return;
	}
	
	var params = mods.util.getParams(mes, 2);
	if (params.size() <= 2) {
		irc.sendContextReply(mes, "Syntax: Feeds.SetDebug <flag> <enabled>");
		return;
	}
	var flag    = String(params.get(1)).trim();
	var enabled = String(params.get(2)).trim();
	enabled = ((enabled == "1") || (enabled == "on") || (enabled == "true") || (enabled == "yes"));
	
	if (flag == "profile") {
		if (enabled) {
			irc.sendContextReply(mes, "Debug profiling enabled.");
		} else {
			irc.sendContextReply(mes, "Debug profiling disabled.");
		}
	} else if (flag == "interval") {
		if (enabled) {
			irc.sendContextReply(mes, "Debug interval timing enabled.");
		} else {
			irc.sendContextReply(mes, "Debug interval timing disabled.");
		}
	} else if (flag == "store") {
		if (enabled) {
			irc.sendContextReply(mes, "Debug feed store enabled.");
		} else {
			irc.sendContextReply(mes, "Debug feed store disabled.");
		}
	} else if (flag == "xml") {
		if (enabled) {
			irc.sendContextReply(mes, "Debug XML parser enabled.");
		} else {
			irc.sendContextReply(mes, "Debug XML parser disabled.");
		}
	} else if (flag == "trace") {
		if (enabled) {
			profile.showRunningTrace = true;
			irc.sendContextReply(mes, "Debug execution trace enabled.");
		} else {
			profile.showRunningTrace = false;
			irc.sendContextReply(mes, "Debug execution trace disabled.");
		}
	} else {
		irc.sendContextReply(msg, "Unknown flag specified. Must be one of 'profile', 'interval', 'store', 'xml' or 'trace'.");
		return;
	}
	this["_debug_" + flag] = enabled;
}
Feeds.prototype.commandSetDebug.help = [
		"Sets debug mode on or off.",
		"<flag> <enabled>",
		"<flag> is one of 'profile', 'interval', 'store', 'xml' or 'trace', so specify what to debug",
		"<enabled> is either 'true' or 'false' to set"
	];


// Command: Status
Feeds.prototype.commandStatus = function(mes, mods, irc) {
	irc.sendContextReply(mes, "Feeds Status: " + this._debugStatus);
}
Feeds.prototype.commandStatus.help = [
		"Shows the current debugging status of the Feeds plugin.",
		""
	];


// Command: Info
//Feeds.prototype.commandInfo = function(mes, mods, irc) {
//	
//	//irc.sendContextReply(mes, "Error getting SVN info: " + ex);
//}
//Feeds.prototype.commandInfo.help = [
//		"Stuff."
//	];


Feeds.prototype._getOwnerFrom = function(nick) {
	var primary = this._mods.nick.getBestPrimaryNick(nick);
	var root = this._mods.security.getRootUser(primary);
	if (root) {
		return String(root);
	}
	return String(primary);
}

Feeds.prototype._getFeed = function(name) {
	for (var i = 0; i < this._feedList.length; i++) {
		var feed = this._feedList[i];
		if (feed.name.toLowerCase() == name.toLowerCase()) {
			return feed;
		}
	}
	return null;
}

Feeds.prototype._canAdminFeed = function(feed, mes) {
	if (this._mods.security.hasPerm(new ChoobPermission("feeds.edit"), mes)) {
		return true; // plugin admin
	}
	if (feed.owner == this._getOwnerFrom(mes.getNick())) {
		return true; // feed owner
	}
	return false;
}

Feeds.prototype._removeFeed = function(feed) {
	for (var i = 0; i < this._feedList.length; i++) {
		if (this._feedList[i] == feed) {
			this._mods.odb["delete"](this._feedList[i]);
			this._feedList.splice(i, 1);
			return;
		}
	}
}

Feeds.prototype._setStatus = function(msg) {
	this._debugStatus = "[" + (new Date()) + "] " + msg;
}

Feeds.prototype._ = function() {
}

// Interval: feed-check
Feeds.prototype._feedCheckInterval = function(param, mods, irc) {
	if (this._feedCheckLock)
		return;
	this._feedCheckLock = true;
	if (this._debug_interval) {
		log("Interval: start");
	}
	this._setStatus("Checking feeds...");
	
	for (var i = 0; i < this._feedList.length; i++) {
		var feed = this._feedList[i];
		if (!feed.safeToCheck()) {
			continue;
		}
		
		if (this._debug_interval) {
			log("Interval:   checking " + feed.name + " (" + -this._feedList[i].getNextCheck() + "ms late)");
		}
		this._setStatus("Checking feed " + feed.getDisplayName() + "...");
		if (this._debug_profile) {
			profile.start();
		}
		feed.checkForNewItems();
		if (this._debug_profile) {
			profile.stop(feed.name);
		}
		this._setStatus("Last checked feed " + feed.getDisplayName() + ".");
	}
	
	var nextCheck = 60 * 60 * 1000; // 1 hour
	for (var i = 0; i < this._feedList.length; i++) {
		var feedNextCheck = this._feedList[i].getNextCheck();
		if (feedNextCheck < 0) {
			feedNextCheck = 0;
		}
		if (nextCheck > feedNextCheck) {
			nextCheck = feedNextCheck;
		}
	}
	// Helps to group the calls.
	var extra = 0;
	if (nextCheck > 10000) {
		extra = 5000;
	}
	
	if (this._debug_interval) {
		log("Interval:   next check due in " + nextCheck + "ms" + (extra ? " + " + extra + "ms" : ""));
		log("Interval: end");
	}
	
	this._feedCheckLock = false;
	// Don't return in anything less than 1s.
	mods.interval.callBack("feed-check", nextCheck + extra, 1);
}


function Feed() {
	this.id = 0;
	this.name = "";
	this.displayName = "";
	this.outputTo = "";
	this.url = "";
	this.ttl = 300; // Default TTL
	this.owner = "";
	this.isPrivate = false;
	this.save = function(){};
	this._items = new Array();
	this._error = "";
	this._errorExpires = 0;
	if (arguments.length > 0) {
		this._ctor(arguments[0], arguments[1], arguments[2], arguments[3]);
	}
}

Feed.prototype._ctor = function(parent, name, url, loadContext) {
	this.name = name;
	this.displayName = name;
	this.url = url;
	this.init(parent, loadContext)
}

Feed.prototype.getDisplayName = function() {
	if (this.displayName)
		return "'" + this.displayName + "' (" + this.name + ")";
	return this.name;
}

Feed.prototype.init = function(parent, loadContext) {
	profile.enterFn("Feed(" + this.name + ")", "init");
	this._parent = parent;
	this._loadContext = loadContext;
	this._outputTo = new Array();
	if (this.outputTo) {
		this._outputTo = this.outputTo.split(" ");
		this._outputTo.sort();
	}
	
	this._cachedContents = null;
	this._lastSeen = new Object();
	this._lastSeenPub = new Object();
	this._lastItemCount = 0;
	this._lastCheck = 0;
	this._lastLoaded = 0;
	if (this._parent._debug_store) {
		log("Feed Store: " + this.name + ": " + this._items.length);
	}
	profile.leaveFn("init");
}

Feed.prototype.addOutputTo = function(destination) {
	for (var i = 0; i < this._outputTo.length; i++) {
		if (this._outputTo[i].toLowerCase() == destination.toLowerCase()) {
			return false;
		}
	}
	this._outputTo.push(destination);
	this._outputTo.sort();
	this.outputTo = this._outputTo.join(" ");
	this.save();
	return true;
}

Feed.prototype.removeOutputTo = function(destination) {
	for (var i = 0; i < this._outputTo.length; i++) {
		if (this._outputTo[i].toLowerCase() == destination.toLowerCase()) {
			this._outputTo.splice(i, 1);
			this.outputTo = this._outputTo.join(" ");
			this.save();
			return true;
		}
	}
	return false;
}

Feed.prototype.getError = function() {
	if (this._error) {
		return this._error + " [expires " + (new Date(this._errorExpires)) + "]";
	}
	return "";
}

Feed.prototype.setError = function(msg) {
	this._error = msg;
	this._errorExpires = Number(new Date()) + 60 * 60 * 1000; // 1 hour
}

Feed.prototype.getSendTo = function() {
	var st = new Array();
	for (var i = 0; i < this._outputTo.length; i++) {
		st.push(this._outputTo[i]);
	}
	return st;
}

Feed.prototype.getLastLoaded = function() {
	if (this._lastLoaded == 0)
		return "never loaded";
	return ("loaded " + this._lastLoaded);
}

// Return boolean indicating if it is ok to reload the contents of the feed.
Feed.prototype.safeToCheck = function() {
	// If the error has expired, clear it.
	if (this._error && (this._errorExpires < Number(new Date()))) {
		this._error = "";
		this._errorExpires = 0;
	}
	if (this._error) {
		return false;
	}
	
	// <ttl> min delay. Default is 1m.
	var checkTime = Number(new Date()) - (this.ttl * 1000);
	return (Number(this._lastCheck) < checkTime);
}

// Return the number of milliseconds until the next checkpoint.
Feed.prototype.getNextCheck = function() {
	var delay = (this._lastCheck ? Number(this._lastCheck) - Number(new Date()) + (this.ttl * 1000) : 0);
	if (this._error) {
		delay = this._errorExpires - Number(new Date());
	}
	
	return delay;
}

Feed.prototype.showRecent = function(target, offset, count) {
	if (this.getError()) {
		this._sendTo(target, this.getDisplayName() + ": \x02ERROR\x02: " + this.getError());
		return;
	}
	
	var items = this._items;
	
	if (items.length == 0) {
		if (this._lastCheck == 0) {
			this._sendTo(target, this.getDisplayName() + " has not loaded yet.");
		} else {
			this._sendTo(target, this.getDisplayName() + " has no recent items.");
		}
		return;
	}
	
	var start = items.length - 1; // Default to the last item.
	if (start > offset + count) {
		start = offset + count - 1;
	}
	if (start > items.length - 1) {
		// Make sure not to start before the oldest item we have.
		start = items.length - 1;
	}
	for (var i = start; i >= offset; i--) {
		if (items[i].desc.indexOf(items[i].title) > -1) {
			this._sendTo(target, "[" + new Date(items[i].date) + "] " + items[i].desc, (items[i].link ? " <" + items[i].link + ">" : ""));
		} else {
			this._sendTo(target, "[" + new Date(items[i].date) + "] \x1F" + items[i].title + "\x1F " + items[i].desc, (items[i].link ? " <" + items[i].link + ">" : ""));
		}
	}
}

Feed.prototype.checkForNewItems = function() {
	profile.enterFn("Feed(" + this.name + ")", "checkForNewItems");
	if (this.getError()) {
		profile.leaveFn("checkForNewItems");
		return;
	}
	
	var firstRun = (this._lastCheck == 0);
	var newItems = this.getNewItems();
	
	if (this.getError()) {
		if (firstRun && this._loadContext) {
			// We're trying to load the feed, and it failed. Oh the humanity.
			this._parent._irc.sendMessage(this._loadContext, this.getDisplayName() + " failed to load, incurring the error: " + this.getError());
		}
		//this._sendToAll(this.getDisplayName() + ": \x02ERROR\x02: " + this.getError());
		profile.leaveFn("checkForNewItems");
		return;
	}
	this._lastLoaded = new Date();
	
	if (firstRun && this._loadContext) {
		this._parent._irc.sendMessage(this._loadContext, this.getDisplayName() + " loaded with " + this._lastItemCount + " items.");
	}
	
	// If there are more than 3 items, and it's more than 20% of the feed's
	// length, don't display the items. This allows feeds with more items (e.g.
	// news feeds) to flood a little bit more, but still prevents a feed from
	// showing all it's items if it just added them all.
	// Never bother with more than 10 items, whatever.
	if ((newItems.length > 10) || ((newItems.length > 3) && (newItems.length > 0.20 * this._lastItemCount))) {
		this._sendToAll(this.getDisplayName() + " has too many (" + newItems.length + ") new items to display.");
	} else {
		for (var i = newItems.length - 1; i >= 0; i--) {
			if (newItems[i].desc.indexOf(newItems[i].title) > -1) {
				this._sendToAll(newItems[i].desc, (newItems[i].link ? " <" + newItems[i].link + ">" : ""));
			} else if (newItems[i].updated) {
				this._sendToAll("\x1F" + newItems[i].title + "\x1F " + newItems[i].desc, (newItems[i].link ? " <" + newItems[i].link + ">" : ""));
			} else {
				this._sendToAll("\x1F\x02" + newItems[i].title + "\x02\x1F " + newItems[i].desc, (newItems[i].link ? " <" + newItems[i].link + ">" : ""));
			}
		}
	}
	profile.leaveFn("checkForNewItems");
}

Feed.prototype.ensureCachedContents = function() {
	profile.enterFn("Feed(" + this.name + ")", "ensureCachedContents");
	try {
		if (!this._cachedContents) {
			var urlObj = new URL(this.url);
			profile.enterFn("Feed(" + this.name + ")", "ensureCachedContents.getContentsCached");
			this._cachedContents = new GetContentsCached(urlObj, 60000);
			profile.leaveFn("ensureCachedContents.getContentsCached");
		}
	} catch(ex) {
		// Error = no items.
		this.setError("Exception getting data: " + ex + (ex.fileName ? " at <" + ex.fileName + ":" + ex.lineNumber + ">":""));
		profile.leaveFn("ensureCachedContents");
		return false;
	}
	profile.leaveFn("ensureCachedContents");
	return true;
}

Feed.prototype.getNewItems = function() {
	profile.enterFn("Feed(" + this.name + ")", "getNewItems");
	
	if (!this.ensureCachedContents()) {
		profile.leaveFn("getNewItems");
		return [];
	}
	
	var feedData = "";
	profile.enterFn("Feed(" + this.name + ")", "getNewItems.getCachedContents");
	try {
		feedData = String(this._cachedContents.getContents());
	} catch(ex) {
		profile.leaveFn("getNewItems.getCachedContents");
		// Error = no items.
		this.setError("Exception getting data: " + ex + (ex.fileName ? " at <" + ex.fileName + ":" + ex.lineNumber + ">":""));
		profile.leaveFn("getNewItems");
		return [];
	}
	profile.leaveFn("getNewItems.getCachedContents");
	
	if (feedData == "") {
		this.setError("Unable to fetch data");
		profile.leaveFn("getNewItems");
		return [];
	}
	
	try {
		profile.enterFn("FeedParser(" + this.name + ")", "new");
		var feedParser = new FeedParser(this._parent, feedData);
		profile.leaveFn("new");
	} catch(ex) {
		this.setError("Exception in parser: " + ex + (ex.fileName ? " at <" + ex.fileName + ":" + ex.lineNumber + ">":""));
		profile.leaveFn("getNewItems");
		return [];
	}
	
	var firstTime = true;
	var curItems = new Object();
	for (var d in this._lastSeen) {
		firstTime = false;
		this._lastSeen[d] = false;
	}
	for (var d in this._lastSeenPub) {
		this._lastSeenPub[d] = false;
	}
	
	// Force TTL to be >= that in the feed itself.
	if (feedParser.ttl && (this.ttl < feedParser.ttl)) {
		this.ttl = feedParser.ttl;
		this.save();
	}
	
	// Update title if it's different.
	if (feedParser.title) {
		var feedTitle = feedParser.title.replace(/^\s+/, "").replace(/\s+$/, "");
		if (this.displayName != feedTitle) {
			this.displayName = feedTitle;
			this.save();
		}
	}
	
	var newItems = new Array();
	var newUniques = new Object();
	
	// Only keep new or updated items in the list.
	for (var i = 0; i < feedParser.items.length; i++) {
		var item = feedParser.items[i];
		
		// Prefer, in order: GUID, link, date, title.
		var unique = (item.guid ? item.guid : (item.link ? item.link : (item.date ? item.date : item.title)));
		var date = unique + ":" + item.date;
		
		if (unique in newUniques) {
			// Skip repeated unique items. Broken feed!
			continue;
		}
		newUniques[unique] = true;
		
		if (unique in this._lastSeen) {
			// Seen this item before. Has it changed?
			if (date in this._lastSeenPub) {
				// No change.
				this._lastSeen[unique] = true;
				this._lastSeenPub[date] = true;
				continue;
			}
			// Items changed.
			item.updated = true;
		}
		
		// New item.
		item.uniqueKey = unique;
		newItems.push(item);
		this._lastSeen[unique] = true;
		this._lastSeenPub[date] = true;
		
		// Remove and re-add from store if it's updated. Just add for new.
		if (item.updated) {
			for (var i = 0; i < this._items.length; i++) {
				if (this._items[i].uniqueKey == item.uniqueKey) {
					if (this._parent._debug_store) {
						log("Feed Store: " + this.name + ": DEL <" + this._items[i].uniqueKey + "><" + this._items[i].date + ">");
					}
					this._items.splice(i, 1);
					break;
				}
			}
		}
		if (this._parent._debug_store) {
			log("Feed Store: " + this.name + ": ADD <" + item.uniqueKey + "><" + item.date + ">");
		}
		this._items.push(item);
	}
	
	for (var d in this._lastSeen) {
		if (!this._lastSeen[d]) {
			delete this._lastSeen[d];
			for (var i = 0; i < this._items.length; i++) {
				if (this._items[i].uniqueKey == d) {
					if (this._parent._debug_store) {
						log("Feed Store: " + this.name + ": DEL <" + this._items[i].uniqueKey + "><" + this._items[i].date + ">");
					}
					this._items.splice(i, 1);
					break;
				}
			}
		}
	}
	for (var d in this._lastSeenPub) {
		if (!this._lastSeenPub[d]) {
			delete this._lastSeenPub[d];
		}
	}
	
	if (this._parent._debug_store) {
		log("Feed Store: " + this.name + ": " + this._items.length);
	}
	
	var count = 0;
	for (var d in this._lastSeenPub) {
		count++;
	}
	this._lastItemCount = count;
	
	if (firstTime) {
		newItems = new Array();
	}
	this._lastCheck = new Date();
	
	profile.leaveFn("getNewItems");
	return newItems;
}

Feed.prototype._sendToAll = function(message, suffix) {
	for (var i = 0; i < this._outputTo.length; i++) {
		this._sendTo(this._outputTo[i], message, suffix);
	}
}

Feed.prototype._sendTo = function(target, message, suffix) {
	if (typeof suffix != "string") {
		suffix = "";
	}
	if (message.length + suffix.length > 390) {
		message = message.substr(0, 390 - suffix.length) + "...";
	}
	this._parent._irc.sendMessage(target, message + suffix);
}

var entityMap = {
	"lt":      "<", "#60":     "<",
	"gt":      ">", "#62":     ">",
	"quot":    '"', "#34":     '"',
	"ldquo":   '"', "#8220":   '"',
	"rdquo":   '"', "#8221":   '"',
	"apos":    "'", "#39":     "'",
	"lsquo":   "'", "#8216":   "'",
	"rsquo":   "'", "#8217":   "'",
	"nbsp":    " ", "#160":    " ",
	"ndash":   "-", "#8211":   "-",
	"mdash":   "-", "#8212":   "-",
	"lsaquo": "<<", "#8249":  "<<",
	"rsaquo": ">>", "#8250":  ">>",
	"times":   "x",
	"#163":    "�",
	"#8230":   "...",
	"dummy":   ""
};

function _decodeEntities(data) {
	profile.enterFn("", "_decodeEntities");
	
	// Decode XML into HTML...
	data = data.replace(/&(?:(\w+)|#(\d+)|#x([0-9a-f]{2}));/gi,
	function _decodeEntity(match, name, decnum, hexnum) {
		if (name && (name in entityMap)) {
			return entityMap[name];
		}
		if (decnum && (String("#" + parseInt(decnum, 10)) in entityMap)) {
			return entityMap[String("#" + parseInt(decnum, 10))];
		}
		if (hexnum && (String("#" + parseInt(hexnum, 16)) in entityMap)) {
			return entityMap[String("#" + parseInt(hexnum, 16))];
		}
		return match; //"[unknown entity '" + (name || decnum || hexnum) + "']";
	});
	
	// Done as a special-case, last, so that it doesn't bugger up
	// doubly-escaped things.
	data = data.replace(/&(amp|#0*38|#x0*26);/g, "&");
	
	profile.leaveFn("_decodeEntities");
	return data;
}

var htmlInlineTags = {
	"A": true,
	"ABBR": true,
	"ACRONYM": true,
	"AREA": true,
	"B": true,
	"BASE": true,
	"BASEFONT": true,
	"BDO": true,
	"BIG": true,
	"BUTTON": true,
	"CITE": true,
	"CODE": true,
	"DEL": true,
	"DFN": true,
	"EM": true,
	"FONT": true,
	"I": true,
	"INS": true,
	"ISINDEX": true,
	"KBD": true,
	"LABEL": true,
	"LEGEND": true,
	"LINK": true,
	"MAP": true,
	"META": true,
	"NOSCRIPT": true,
	"OPTGROUP": true,
	"OPTION": true,
	"PARAM": true,
	"Q": true,
	"S": true,
	"SAMP": true,
	"SCRIPT": true,
	"SELECT": true,
	"SMALL": true,
	"SPAN": true,
	"STRIKE": true,
	"STRONG": true,
	"STYLE": true,
	"SUB": true,
	"SUP": true,
	"TEXTAREA": true,
	"TT": true,
	"U": true,
	"VAR": true,
};

function _decodeRSSHTML(data) {
	profile.enterFn("", "_decodeRSSHTML");
	
	// Decode XML into HTML...
	data = _decodeEntities(data);
	// Remove all tags.
	data = data.replace(/<\/?(\w+)[^>]*>/g, function (text, tag) { return tag.toUpperCase() in htmlInlineTags ? "" : " " });
	// Decode HTML into text...
	data = _decodeEntities(data);
	// Remove all entities.
	//data = data.replace(/&[^;]+;/g, "");
	data = data.replace(/\s+/g, " ");
	
	profile.leaveFn("_decodeRSSHTML");
	return data;
}

function _decodeRSSDate(element) {
	if (element && element.contents()) {
		try {
			return Number(new Date(element.contents()));
		} catch(ex) {
		}
	}
	return 0;
}

function _decodeAtomText(element) {
	if (!element)
		return "";
	
	var content = element.contents();
	var type = element.attribute("type");
	
	if (type && (type.value == "html")) {
		return _decodeRSSHTML(content);
	}
	
	return _decodeEntities(content);
}

function _decodeAtomDate(element) {
	var ary = element.contents().match(/^(\d+)-(\d+)-(\d+)T(\d+):(\d+):(\d+)(?:.(\d+))?(Z|([+-])(\d+):(\d+))$/);
	if (ary) {
		var d = new Date(ary[1], ary[2] - 1, ary[3], ary[4], ary[5], ary[6]);
		var ts = Number(d);
		// Nullify any local timezone picked by new Date().
		ts -= d.getTimezoneOffset() * 60 * 1000;
		// [RFC3339]
		// Numeric offsets are calculated as "local time minus UTC".  So the
		// equivalent time in UTC can be determined by subtracting the offset
		// from the local time.
		if (ary[9] == "+") {
			ts -= ary[10] * 60 * 60 * 1000 + ary[11] * 60 * 1000;
		}
		if (ary[9] == "-") {
			ts += ary[10] * 60 * 60 * 1000 + ary[11] * 60 * 1000;
		}
		return ts;
	}
	return 0;
}



// Generic feed parser.
function FeedParser(feedsOwner, data) {
	profile.enterFn("FeedParser", "init.replace");
	data = data.replace(/[\r\n\s]+/, " ");
	profile.leaveFn("init.replace");
	profile.enterFn("XMLParser", "new");
	try {
		this._xmlData = new XMLParser(data);
	} finally {
		profile.leaveFn("new");
	}
	
	this._parse(feedsOwner);
}

FeedParser.prototype.toString = function() {
	return "FeedParser<" + this.title + ">";
}

FeedParser.prototype._parse = function(feedsOwner) {
	profile.enterFn("FeedParser", "_parse");
	this.title = "";
	this.link = "";
	this.description = "";
	this.language = "";
	this.ttl = 0;
	this.items = new Array();
	this.error = "";
	
	var ATOM_1_0_NS = "http://www.w3.org/2005/Atom";
	
	function getChildContents(elt, name, namespace) {
		profile.enterFn("FeedParser", "getChildContents");
		var child = elt.childByName(name, namespace);
		if (child) {
			profile.leaveFn("getChildContents");
			return child.contents();
		}
		profile.leaveFn("getChildContents");
		return "";
	};
	
	// Check what kind of feed we have!
	if (this._xmlData.rootElement.localName == "rss") {
		var rssVersion = this._xmlData.rootElement.attribute("version");
		
		if (rssVersion && ((rssVersion.value == 0.91) || (rssVersion.value == 2.0))) {
			// RSS 0.91 or 2.0 code.
			var channel = this._xmlData.rootElement.childByName("channel");
			
			this.title       = getChildContents(channel, "title").trim();
			this.link        = getChildContents(channel, "link").trim();
			this.description = getChildContents(channel, "description").trim();
			this.language    = getChildContents(channel, "language").trim();
			this.ttl         = getChildContents(channel, "ttl").trim();
			
			var items = channel.childrenByName("item");
			
			for (var i = 0; i < items.length; i++) {
				var item = items[i];
				
				var pubDate = _decodeRSSDate(item.childByName("pubDate"));
				
				var guid  = item.childByName("guid") || "";
				if (guid) {
					guid = guid.contents();
				}
				
				var title = item.childByName("title") || "";
				if (title) {
					title = title.contents();
				}
				
				var link  = item.childByName("link") || "";
				if (link) {
					link = link.contents();
				}
				
				var desc = item.childByName("description") || "";
				if (desc) {
					desc = desc.contents();
				}
				
				this.items.push({
						date:    pubDate,
						guid:    guid.trim(),
						title:   _decodeRSSHTML(title).trim(),
						link:    _decodeEntities(link).trim(),
						desc:    _decodeRSSHTML(desc).trim(),
						updated: false
					});
			}
		} else {
			if (rssVersion) {
				profile.leaveFn("_parse");
				throw new Error("Unsuported RSS version: " + rssVersion.value);
			}
			profile.leaveFn("_parse");
			throw new Error("Unsuported RSS version: <unknown>");
		}
	} else if (this._xmlData.rootElement.localName == "RDF") {
		// RSS 1.0 probably.
		if (this._xmlData.rootElement.namespace == "http://www.w3.org/1999/02/22-rdf-syntax-ns#") {
			
			var channel = this._xmlData.rootElement.childByName("channel", "http://purl.org/rss/1.0/");
			
			this.title       = getChildContents(channel, "title",       "http://purl.org/rss/1.0/").trim();
			this.link        = getChildContents(channel, "link",        "http://purl.org/rss/1.0/").trim();
			this.description = getChildContents(channel, "description", "http://purl.org/rss/1.0/").trim();
			this.language    = getChildContents(channel, "language",    "http://purl.org/rss/1.0/").trim();
			this.ttl         = getChildContents(channel, "ttl",         "http://purl.org/rss/1.0/").trim();
			
			var items = this._xmlData.rootElement.childrenByName("item", "http://purl.org/rss/1.0/");
			
			for (var i = 0; i < items.length; i++) {
				var item = items[i];
				
				var pubDate = _decodeRSSDate(item.childByName("pubDate", "http://purl.org/rss/1.0/"));
				
				var title = item.childByName("title", "http://purl.org/rss/1.0/") || "";
				if (title) {
					title = title.contents();
				}
				
				var link  = item.childByName("link", "http://purl.org/rss/1.0/") || "";
				if (link) {
					link = link.contents();
				}
				
				var desc = item.childByName("description", "http://purl.org/rss/1.0/") || "";
				if (desc) {
					desc = desc.contents();
				}
				
				this.items.push({
						date:    pubDate,
						title:   _decodeRSSHTML(title).trim(),
						link:    _decodeEntities(link).trim(),
						desc:    _decodeRSSHTML(desc).trim(),
						updated: false
					});
			}
			
		} else {
			profile.leaveFn("_parse");
			throw new Error("Unsuported namespace: " + this._xmlData.rootElement.namespace);
		}
		
	} else if (this._xmlData.rootElement.is("feed", ATOM_1_0_NS)) {
		// Atom 1.0.
		
		// Text decoder: _decodeAtomText(element)
		// Date decoder: _decodeAtomDate(element);
		
		var feed = this._xmlData.rootElement;
		this.title = _decodeAtomText(feed.childByName("title", ATOM_1_0_NS)).trim();
		
		var items = feed.childrenByName("entry", ATOM_1_0_NS);
		
		for (var i = 0; i < items.length; i++) {
			var item = items[i];
			
			var updated = _decodeAtomDate(item.childByName("updated", ATOM_1_0_NS));
			
			var title = _decodeAtomText(item.childByName("title", ATOM_1_0_NS));
			
			var link  = item.childByName("link", ATOM_1_0_NS);
			if (link) {
				link = link.attribute("href");
				if (link) {
					link = link.value;
				} else {
					link = "";
				}
			} else {
				link = "";
			}
			
			var desc  = _decodeAtomText(item.childByName("content", ATOM_1_0_NS));
			
			this.items.push({
					date:    updated,
					title:   title.trim(),
					link:    link.trim(),
					desc:    desc.trim(),
					updated: false
				});
		}
		
	} else {
		profile.leaveFn("_parse");
		throw new Error("Unsupported feed type: " + this._xmlData.rootElement);
	}
	if (feedsOwner && feedsOwner._debug_xml) {
		var limit = { value: 25 };
		log("# URL        : " + this.link);
		log("# TITLE      : " + this.title);
		log("# DESCRIPTION: " + this.description);
		this._xmlData._dump(this._xmlData.root, "", limit);
	}
	profile.leaveFn("_parse");
}



// #include JavaScriptXML.jsi
// General XML parser, win!
function XMLParser(data) {
	this.data = data;
	this.root = [];
	this._state = [];
	this._parse();
}

XMLParser.prototype._dumpln = function(line, limit) {
	limit.value--;
	if (limit.value == 0) {
		dumpln("*** TRUNCATED ***");
	}
	if (limit.value <= 0) {
		return;
	}
	dumpln(line);
}

XMLParser.prototype._dump = function(list, indent, limit) {
	for (var i = 0; i < list.length; i++) {
		this._dumpElement(list[i], indent, limit);
	}
}

XMLParser.prototype._dumpElement = function(elt, indent, limit) {
	if (elt._content) {
		this._dumpln(indent + elt + elt._content + "</" + elt.name + ">", limit);
	} else if (elt._children && (elt._children.length > 0)) {
		this._dumpln(indent + elt, limit);
		this._dump(elt._children, indent + "  ", limit);
		this._dumpln(indent + "</" + elt.name + ">", limit);
	} else {
		this._dumpln(indent + elt, limit);
	}
}

XMLParser.prototype._parse = function() {
	// Hack off the Unicode DOM if it exists.
	if (this.data.substr(0, 3) == "\xEF\xBB\xBF") {
		this.data = this.data.substr(3);
	}
	
	// Process all entities here.
	this._processEntities();
	
	// Head off for the <?xml PI.
	while (this.data.length > 0) {
		this._eatWhitespace();
		
		if (this.data.substr(0, 4) == "<!--") {
			// Comment.
			this.root.push(this._eatComment());
			
		} else if (this.data.substr(0, 2) == "<!") {
			// SGML element.
			this.root.push(this._eatSGMLElement());
			
		} else if (this.data.substr(0, 2) == "<?") {
			var e = this._eatElement(null);
			if (e.name != "xml") {
				throw new Error("Expected <?xml?>, found <?" + e.name + "?>");
			}
			this.xmlPI = e;
			this.root.push(e);
			break;
			
		} else {
			break;
			//throw new Error("Expected <?xml?>, found " + this.data.substr(0, 10) + "...");
		}
	}
	
	// OK, onto the root element...
	while (this.data.length > 0) {
		this._eatWhitespace();
		
		if (this.data.substr(0, 4) == "<!--") {
			// Comment.
			this.root.push(this._eatComment());
			
		} else if (this.data.substr(0, 2) == "<!") {
			// SGML element.
			this.root.push(this._eatSGMLElement());
			
		} else if (this.data.substr(0, 2) == "<?") {
			var e = this._eatElement(null);
			this.root.push(e);
			
		} else if (this.data.substr(0, 1) == "<") {
			var e = this._eatElement(null);
			if (e.start == false) {
				throw new Error("Expected start element, found end element");
			}
			this.rootElement = e;
			this.root.push(e);
			this._state.unshift(e);
			break;
			
		} else {
			throw new Error("Expected root element, found " + this.data.substr(0, 10) + "...");
		}
	}
	
	// Now the contents.
	while (this.data.length > 0) {
		this._eatWhitespace();
		
		if (this.data.substr(0, 4) == "<!--") {
			// Comment.
			this._state[0]._children.push(this._eatComment());
			
		} else if (this.data.substr(0, 2) == "<!") {
			// SGML element.
			this._state[0]._children.push(this._eatSGMLElement());
			
		} else if (this.data[0] == "<") {
			var e = this._eatElement(this._state[0]);
			if (e.empty) {
				this._state[0]._children.push(e);
			} else if (e.start) {
				this._state[0]._children.push(e);
				this._state.unshift(e);
			} else {
				if (e.name != this._state[0].name) {
					throw new Error("Expected </" + this._state[0].name + ">, found </" + e.name + ">");
				}
				this._state.shift();
				if (this._state.length == 0) {
					// We've ended the root element, that's it folks!
					break;
				}
			}
			
		} else {
			var pos = this.data.indexOf("<");
			if (pos < 0) {
				this._state[0]._content = this.data;
				this.data = "";
			} else {
				this._state[0]._content = this.data.substr(0, pos);
				this.data = this.data.substr(pos);
			}
		}
	}
	
	// Eat any trailing spaces and comments.
	while (this.data.length > 0) {
		this._eatWhitespace();
		
		if (this.data.substr(0, 4) == "<!--") {
			// Comment.
			this.root.push(this._eatComment());
			
		} else if (this.data.length > 0) {
			throw new Error("Expected EOF or comment, found " + this.data.substr(0, 10) + "...");
		}
	}
	
	if (this._state.length > 0) {
		throw new Error("Expected </" + this._state[0].name + ">, found EOF.");
	}
	if (this.data.length > 0) {
		throw new Error("Expected EOF, found " + this.data.substr(0, 10) + "...");
	}
}

XMLParser.prototype._processEntities = function() {}
XMLParser.prototype._processEntities_TODO = function(string) {
	var i = 0;
	while (i < string.length) {
		// Find next &...
		i = string.indexOf("&", i);
		
		//if (string.substr(i, 4) == "&lt;") {
		//	this.data = string.substr(0, i - 1) + "<" + 
		
		// Make sure we skip over the character we just inserted.
		i++;
	}
	
	return string;
}

XMLParser.prototype._eatWhitespace = function() {
	var len = this._countWhitespace();
	if (len > 0) {
		this.data = this.data.substr(len);
	}
}

XMLParser.prototype._countWhitespace = function() {
	// Optimise by checking only first character first.
	if (this.data.length <= 0) {
		return 0;
	}
	var ws = this.data[0].match(/^\s+/);
	if (ws) {
		// Now check first 256 characters.
		ws = this.data.substr(0, 256).match(/^\s+/);
		
		if (ws[0].length == 256) {
			// Ok, check it all.
			ws = this.data.match(/^\s+/);
			return ws[0].length;
		}
		return ws[0].length;
	}
	return 0;
}

XMLParser.prototype._eatComment = function() {
	if (this.data.substr(0, 4) != "<!--") {
		throw new Error("Expected <!--, found " + this.data.substr(0, 10) + "...");
	}
	var i = 4;
	while (i < this.data.length) {
		if (this.data.substr(i, 3) == "-->") {
			// Done.
			var c = new XMLComment(this.data.substr(4, i - 4));
			this.data = this.data.substr(i + 3);
			return c;
		}
		i++;
	}
	throw new Error("Expected -->, found EOF.");
}

XMLParser.prototype._eatSGMLElement = function() {
	if (this.data.substr(0, 2) != "<!") {
		throw new Error("Expected <!, found " + this.data.substr(0, 10) + "...");
	}
	
	// CDATA chunk?
	if (this.data.substr(0, 9) == "<![CDATA[") {
		return this._eatCDATAElement();
	}
	
	var i = 2;
	var inQuote = "";
	while (i < this.data.length) {
		if (inQuote == this.data[i]) {
			inQuote = "";
			
		} else if ((this.data[i] == "'") || (this.data[i] == '"')) {
			inQuote = this.data[i];
			
		} else if (this.data[i] == ">") {
			// Done.
			var c = new XMLComment(this.data.substr(2, i - 1));
			this.data = this.data.substr(i + 1);
			return c;
		}
		i++;
	}
	throw new Error("Expected >, found EOF.");
}

XMLParser.prototype._eatCDATAElement = function() {
	if (this.data.substr(0, 9) != "<![CDATA[") {
		throw new Error("Expected <![CDATA[, found " + this.data.substr(0, 20) + "...");
	}
	
	var i = 9;
	while (i < this.data.length) {
		if ((this.data[i] == "]") && (this.data.substr(i, 3) == "]]>")) {
			// Done.
			var e = new XMLCData(this.data.substr(9, i - 9));
			this.data = this.data.substr(i + 3);
			return e;
		}
		i++;
	}
	throw new Error("Expected ]]>, found EOF.");
}

XMLParser.prototype._eatElement = function(parent) {
	if (this.data[0] != "<") {
		throw new Error("Expected <, found " + this.data.substr(0, 10) + "...");
	}
	
	var whitespace = /\s/i;
	var e;
	var name = "";
	var start = true;
	var pi = false;
	var i = 1;
	if (this.data[i] == "?") {
		pi = true;
		i++;
	}
	if (!pi && (this.data[i] == "/")) {
		start = false;
		i++;
	}
	
	while (i < this.data.length) {
		if (!pi && (this.data[i] == ">")) {
			e = new XMLElement(parent, name, start, pi, false);
			this.data = this.data.substr(i + 1);
			e.resolveNamespaces();
			return e;
			
		} else if (start && (this.data.substr(i, 2) == "/>")) {
			e = new XMLElement(parent, name, start, pi, true);
			this.data = this.data.substr(i + 2);
			e.resolveNamespaces();
			return e;
			
		} else if (pi && (this.data.substr(i, 2) == "?>")) {
			e = new XMLElement(parent, name, start, pi, false);
			this.data = this.data.substr(i + 2);
			e.resolveNamespaces();
			return e;
			
		} else if (whitespace.test(this.data[i])) {
			// End of name.
			e = new XMLElement(parent, name, start, pi, false);
			i++;
			break;
			
		} else {
			name += this.data[i];
		}
		i++;
	}
	
	// On to attributes.
	name = "";
	var a = "";
	var inName = false;
	var inEQ = false;
	var inVal = false;
	var inQuote = "";
	while (i < this.data.length) {
		if (!pi && !inName && !inEQ && !inVal && (this.data[i] == ">")) {
			this.data = this.data.substr(i + 1);
			e.resolveNamespaces();
			return e;
			
		} else if (!pi && !inName && !inEQ && !inVal && (this.data.substr(i, 2) == "/>")) {
			if (!e.start) {
				throw new Error("Invalid end tag, found " + this.data.substr(0, i + 10) + "...");
			}
			e.empty = true;
			this.data = this.data.substr(i + 2);
			e.resolveNamespaces();
			return e;
			
		} else if (pi && !inName && !inEQ && !inVal && (this.data.substr(i, 2) == "?>")) {
			this.data = this.data.substr(i + 2);
			e.resolveNamespaces();
			return e;
			
		} else if (inName && (this.data[i] == "=")) {
			inName = false;
			inEQ = true;
			
		} else if (inEQ && ((this.data[i] == '"') || (this.data[i] == "'"))) {
			inEQ = false;
			inVal = true;
			inQuote = this.data[i];
			
		} else if (inQuote && ((this.data[i] == '"') || (this.data[i] == "'"))) {
			if (inQuote == this.data[i]) {
				inQuote = "";
				inVal = false;
				e._attributes.push(new XMLAttribute(e, name, a));
				name = "";
				a = "";
			}
			
		} else if (whitespace.test(this.data[i])) {
			if (inVal && !inQuote) {
				inVal = false;
				e._attributes.push(new XMLAttribute(e, name, a));
				name = "";
				a = "";
			}
			
		} else if (inEQ || inVal) {
			if (inEQ) {
				inEQ = false;
				inVal = true;
				a = "";
			}
			a += this.data[i];
			
		} else {
			if (!inName) {
				inName = true;
			}
			name += this.data[i];
		}
		i++;
	}
	
	//this.data = this.data.substr(i);
	
	//e.resolveNamespaces();
	//return e;
	throw new Error("Expected >, found EOF.");
}



function XMLElement(parent, name, start, pi, empty) {
	this.type = "XMLElement";
	this.parent = parent;
	this.name = name;
	this.start = start;
	this.pi = pi;
	this.empty = empty;
	this.namespace = "";
	
	var ary = this.name.match(/^(.*?):(.*)$/);
	if (ary) {
		this.prefix = ary[1];
		this.localName = ary[2];
	} else {
		this.prefix = null;
		this.localName = this.name;
	}
	
	this._attributes = [];
	this._content = "";
	this._children = [];
}

XMLElement.prototype.toString = function() {
	var str = "<";
	if (this.pi) {
		str += "?";
	} else if (!this.start) {
		str += "/";
	}
	if (this.prefix != null) {
		str += this.prefix + ":";
	}
	str += this.localName;
	if (this.namespace) {
		str += "[[" + this.namespace + "]]";
	}
	for (var a in this._attributes) {
		str += " " + this._attributes[a];
	}
	if (this.pi) {
		str += "?";
	}
	if (this.empty || ((this._content == "") && (this._children.length == 0))) {
		str += "/";
	}
	str += ">";
	
	return str;
}

XMLElement.prototype.resolveNamespaces = function() {
	function getNameSpaceFromPrefix(base, pfx) {
		var attrName = "xmlns";
		if (pfx) {
			attrName = "xmlns:" + pfx;
		}
		
		var element = base;
		while (element) {
			var attr = element.attribute(attrName);
			if (attr) {
				return attr.value;
			}
			element = element.parent;
		}
		return "";
	};
	
	this.namespace = getNameSpaceFromPrefix(this, this.prefix);
	
	for (var i = 0; i < this._attributes.length; i++) {
		if (/^xmlns(?:$|:)/.test(this._attributes[i].name)) {
			continue;
		}
		this._attributes[i].namespace = getNameSpaceFromPrefix(this, this._attributes[i].prefix);
	}
}

XMLElement.prototype.is = function(localName, namespace) {
	return (this.localName == localName) && (this.namespace == namespace);
}

XMLElement.prototype.contents = function() {
	var str = this._content;
	if ((this._content == "") && (this._children.length > 0)) {
		str = "";
		for (var i = 0; i < this._children.length; i++) {
			str += this._children[i].contents();
		}
	}
	return str;
}

XMLElement.prototype.attribute = function(name, namespace) {
	for (var i = 0; i < this._attributes.length; i++) {
		if ((typeof namespace != "undefined") && (this._attributes[i].namespace != namespace)) {
			continue;
		}
		if (this._attributes[i].name == name) {
			return this._attributes[i];
		}
	}
	return null;
}

XMLElement.prototype.childrenByName = function(localName, namespace) {
	var rv = [];
	for (var i = 0; i < this._children.length; i++) {
		if ((typeof namespace != "undefined") && (this._children[i].namespace != namespace)) {
			continue;
		}
		if (this._children[i].localName == localName) {
			rv.push(this._children[i]);
		}
	}
	return rv;
}

XMLElement.prototype.childByName = function(localName, namespace) {
	var l = this.childrenByName(localName, namespace);
	if (l.length != 1) {
		return null;
	}
	return l[0];
}



function XMLAttribute(parent, name, value) {
	this.type = "XMLAttribute";
	this.parent = parent;
	this.name = name;
	this.value = value;
	this.namespace = "";
	
	var ary = this.name.match(/^(.*?):(.*)$/);
	if (ary) {
		this.prefix = ary[1];
		this.localName = ary[2];
	} else {
		this.prefix = null;
		this.localName = this.name;
	}
}

XMLAttribute.prototype.toString = function() {
	var str = "";
	if (this.prefix != null) {
		str += this.prefix + ":";
	}
	str += this.localName;
	if (this.namespace) {
		str += "[[" + this.namespace + "]]";
	}
	str += "='" + this.value + "'";
	return str;
}



function XMLCData(value) {
	this.type = "XMLCData";
	this.value = value;
}

XMLCData.prototype.toString = function() {
	return "<![CDATA[" + this.value + "]]>";
}

XMLCData.prototype.contents = function() {
	return this.value;
}



function XMLComment(value) {
	this.type = "XMLComment";
	this.value = value;
}

XMLComment.prototype.toString = function() {
	return "<!--" + this.value + "-->";
}

XMLComment.prototype.contents = function() {
	return this.value;
}
// #includeend



function JSProfiler() {
	this.running = false;
	this.showRunningTrace = false;
	this._calls = 0;
}

JSProfiler.prototype.start = function() {
	if (this.running) {
		throw new Error("Can't start profiler when it is already running.");
	}
	this.running = true;
	this._calls = 0;
	this._functions = new Object();
	this._stack = new Array();
	this._lastJumpTime = Number(new Date());
	
	if (this.showRunningTrace) {
		log("PROFILER: START");
	}
}

JSProfiler.prototype.stop = function(title) {
	if (!this.running) {
		throw new Error("Can't stop profiler when it is not running.");
	}
	if (this.showRunningTrace) {
		log("PROFILER: STOP");
	}
	
	this.running = false;
	if (this._calls == 0) {
		//log("No JSPRofiler profiled functions.");
		return;
	}
	
	function makeCol(val, width) {
		val = String(val);
		while (val.length < width) {
			val = " " + val;
		}
		return val;
	};
	
	var keys = new Array();
	for (var key in this._functions) {
		keys.push(key);
	}
	
	var self = this;
	keys.sort(function(a, b) {
			if (self._functions[a].totalTime < self._functions[b].totalTime)
				return  1;
			if (self._functions[a].totalTime > self._functions[b].totalTime)
				return -1;
			if (self._functions[a].callCount < self._functions[b].callCount)
				return  1;
			if (self._functions[a].callCount > self._functions[b].callCount)
				return -1;
			return 0;
		});
	
	if (keys.length == 0) {
		return;
	}
	
	var shownHeaders = false;
	for (var i = 0; i < keys.length; i++) {
		var fn = this._functions[keys[i]];
		// Always print if runTime >= 1000 or in top 3, but drop out for < 100ms anyway.
		//if (((fn.totalTime < 1000) && (i >= 3)) || (fn.totalTime < 100)) {
		if (fn.totalTime < 100) {
			break;
		}
		if (!shownHeaders) {
			log("JSProfiler Dump" + (title ? " for " + title : "") + ":");
			log("  Calls   Actual (ms)  Nested (ms)  Class/Name");
			shownHeaders = true;
		}
		log("  " + makeCol(fn.callCount, 6) + makeCol(fn.runTime, 13) + makeCol(fn.totalTime, 13) + "  " + keys[i]);
	}
}

JSProfiler.prototype.enterFn = function(cls, name) {
	var key = (cls ? cls + "." : "") + name;
	if (!(key in this._functions)) {
		this._functions[key] = { cls: cls, name: name, callCount: 0, totalTime: 0, runTime: 0 };
	}
	if (this.showRunningTrace) {
		var nest = "";
		for (var i = 0; i < this._stack.length; i++) {
			nest += "  ";
		}
		log("PROFILER: " + nest + (cls ? "<" + cls + ">" : "") + name + " {");
	}
	
	var now = Number(new Date());
	
	if (this._stack.length > 0) {
		this._functions[this._stack[this._stack.length - 1].key].runTime += now - this._lastJumpTime;
	}
	
	this._calls++;
	this._functions[key].callCount++;
	this._stack.push({ key: key, name: name, start: now });
	this._lastJumpTime = now;
}

JSProfiler.prototype.leaveFn = function(name) {
	if (this.showRunningTrace) {
		var nest = "";
		for (var i = 1; i < this._stack.length; i++) {
			nest += "  ";
		}
		log("PROFILER: " + nest + "} // " + name);
	}
	
	var now = Number(new Date());
	var items = new Array();
	
	for (var i = this._stack.length - 1; i >= 0; i--) {
		if (this._stack[i].name == name) {
			this._functions[this._stack[i].key].runTime += now - this._lastJumpTime;
			this._functions[this._stack[i].key].totalTime += now - this._stack[i].start;
			if (i != this._stack.length - 1) {
				log("WARNING: leaving function '" + name + "' skipping " + (this._stack.length - 1 - i) + " stack items (" + items.join(", ") + ")!");
			}
			this._stack.splice(i);
			this._lastJumpTime = now;
			return;
		}
		items.push(this._stack[i].key);
	}
	log("WARNING: leaving function '" + name + "' we never entered!");
	this._lastJumpTime = now;
}

var profile = new JSProfiler();
