//TODO: castling, massive memory leak
//Do you get anything for a double bingo?
//x3 gold for double, x6 for triple, etc.
//sanity checks all over the place
//raffle payouts?

package org.chernovia.net.games.bingochess;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Vector;

import org.bson.Document;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import org.chernovia.lib.netgames.zugserv.ConnListener;
import org.chernovia.lib.netgames.zugserv.Connection;
import org.chernovia.lib.netgames.zugserv.WebSockConn;
import org.chernovia.lib.netgames.zugserv.WebSockServ;
import org.chernovia.lichess.*;
import org.chernovia.lichess.gson.GameData;
import org.chernovia.lichess.util.LichessUtils;
import org.chernovia.twitch.TwitchBot;

public class BingoChess extends TwitchBot implements GameWatcher, ConnListener, BingoListener {
	
	public static final long TIMEOUT = 99999; //99.9 seconds, maybe should be shorter?

	class BingoServ extends WebSockServ {

		public BingoServ(int port, ConnListener l) { super(port, l); }
		
		public boolean isLegalName(String n) {
			if (n==null || n.length()<2 || n.length()>32) return false;
			else return true;
		}
	}
	
	ObjectMapper mapper = new ObjectMapper(); 
	String gameType = "blitz";
	MongoClient mongoClient;
	MongoDatabase bingoBase;
	MongoCollection<Document> playData;
	String bingoURL;
	BingoServ serv;
	GameClient tv_client;
	GameData tv_data;
	HashMap<String,BingoPlayer> bingoers;
	HashMap<String,Chatter> twits;
	String lastFEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";
	JsonNode lastBingoPos = null;
	long lastMoveTime;
			
	public static void main(String[] args) {
		WebSockConn.VERBOSITY = 0;
		new BingoChess(args);
		//bingo.newPlayer("Zugx1"); bingo.newPlayer("Zugx2"); bingo.newPlayer("Zugx3");
	}
	
	public BingoChess(String[] args) {
		tv_client = new GameClient();
		twits = new HashMap<String,Chatter>();
		bingoers = new HashMap<String,BingoPlayer>();
		BingoPlayer.SQUARE_BAG = new Vector<Dimension>();
		for (int x=0;x<8;x++)
		for (int y=0;y<8;y++)
		BingoPlayer.SQUARE_BAG.add(new Dimension(x,y));
		initIRC(args[0], args[1], args[2], args[3]);
		loadAdmins("res/admins.txt");
		serv = new BingoServ(Integer.parseInt(args[4]),this);
		serv.startSrv();
		bingoURL = args[5];
		followTVGame();
		MongoClientURI connStr = new MongoClientURI("mongodb://bingobot:" + args[6] + "@localhost:27017/BingoBase");
		mongoClient = new MongoClient(connStr);
		bingoBase = mongoClient.getDatabase("BingoBase");
		playData = bingoBase.getCollection("players");
	}
	
	private BingoPlayer getBingoer(String handle) { return bingoers.get(handle); }
	

	private void newMove(JsonNode data) {
		//log("New Move: " + data);
		boolean wMove = data.get("ply").asInt() % 2 == 0;
		JsonNode clock = data.get("clock");
		double wTime = clock.get("white").asDouble(), bTime = clock.get("black").asDouble();
		String FEN = data.get("fen").asText();
		JsonNode gameObj = gameToJson(FEN,
				tv_data.players.white.userId,tv_data.players.white.rating,wTime,
				tv_data.players.black.userId,tv_data.players.black.rating,bTime,
				wMove);
		for (Connection conn : serv.getAllConnections(true)) conn.tell("tv_game", gameObj); 
		lastBingoPos = gameObj;
		String move = data.get("uci").asText();
		if (move.length() >= 4) {
			int x = move.charAt(2)-'a', y = 8-(move.charAt(3)-'0');
			for (BingoPlayer p: bingoers.values()) p.checkMove(x, y); 
		}
	}
	
	private JsonNode gameToJson(
		String FEN, String w, int w_rat, double w_time, String b, int b_rat, double b_time, boolean w_move) {
		ObjectNode gameObj = mapper.createObjectNode(); 
		gameObj.put("fen",FEN);
		gameObj.put("white", w);
		gameObj.put("white_rating", w_rat);
		gameObj.put("white_time", w_time);
		gameObj.put("black", b);
		gameObj.put("black_rating", b_rat);
		gameObj.put("black_time", b_time);
		gameObj.put("white_move", w_move);
		return gameObj;
	}
	
	private ArrayList<Connection> getConns(String handle) {
		ArrayList<Connection> connections = new ArrayList<Connection>();
		for (Connection c : serv.getAllConnections(true)) {
			if (c.getHandle().equalsIgnoreCase(handle)) connections.add(c);
		}
		return connections;
	}
	
	private void newBingoPlayer(String handle, int fee) {
		bingoers.put(handle,new BingoPlayer(handle,fee,this));
		for (Connection c : serv.getAllConnections(true)) updateBingoers(c); 
	}
	
	private void winner_bingo(BingoPlayer winner) {
		ctell(winner.handle + " has a bingo!");
		playData.updateOne(eq("name",winner.handle),inc("tickets",1));
		playData.updateOne(eq("name",winner.handle),inc("tickets_won",1));
		int gold = 10 + (int)(bingoers.size()/2);
		playData.updateOne(eq("name",winner.handle),inc("gold",gold));
		playData.updateOne(eq("name",winner.handle),inc("gold_won",gold));
		for (Connection conn : serv.getAllConnections(true)) {
			if (getBingoer(conn.getHandle()) != null) {
				conn.tell("newgame","");
				conn.tell("finger",finger(conn.getHandle()));
			}
		}
		bingoers.clear(); //p.observers.clear();
	}
	
	private JsonNode finger(String handle) {
		return playerToJson(playData.find(eq("name",handle)).first());
	}
	
	private ArrayNode top(String crit, int n) {
		FindIterable<Document> docs = playData.find().sort(Sorts.orderBy(Sorts.ascending(crit))).limit(n);
		ArrayNode players = mapper.createArrayNode();
		for (Document doc : docs) players.add(playerToJson(doc));
		return players;
	}
	
	private JsonNode playerToJson(Document doc) {
		if (doc == null) return null;
		ObjectNode obj = mapper.createObjectNode();
		obj.put("name", doc.getString("name"));
		obj.put("tickets",doc.getInteger("tickets").toString());
		obj.put("gold",doc.getInteger("gold").toString());
		obj.put("games",doc.getInteger("games").toString());
		return obj;
	}
	
	private void followTVGame() {
		String gid = LichessUtils.getTVData(gameType)[0];
		while (gid == null || gid.equals("")) { 
			try { Thread.sleep(250); } catch (InterruptedException ignore) {}
			gid = LichessUtils.getTVData(gameType)[0]; 
		}
		followTVGame(gid); 
	}
	
	private void followTVGame(String gid) {
		lastMoveTime = System.currentTimeMillis();
		log("Following: " + gid);
		tv_data = LichessUtils.getGame(gid);
		//newMongoPlayer(tv_data.players.black.userId); //populate the database!
		//newMongoPlayer(tv_data.players.white.userId);
		tv_client.newGame(gid,this);
	}

	private void updateAll(Connection conn) { update(conn,true,true,true); }
	private void update(Connection conn,boolean tv,boolean card,boolean bingoers) {
		BingoPlayer bingoPlayer = card ? getBingoer(conn.getHandle()) : null;
		if (tv) updateTV(conn,lastBingoPos);
		if (card && bingoPlayer != null) bingoPlayer.listener.updateCard(bingoPlayer);;
		if (bingoers) updateBingoers(conn);
	}
	
	private void updateBingoers(Connection conn) {
		ArrayNode binglist = mapper.createArrayNode();
		for (BingoPlayer p :bingoers.values()) if (!p.handle.equals(conn.getHandle())) binglist.add(p.handle);
		conn.tell("binglist", binglist);
	}
	
	private void updateTV(Connection conn, JsonNode position) {
		if (position != null) conn.tell("tv_game", position);
	}
	
	private Chatter getTwit(String handle) { //should this really silently create a Twit?
		if (twits.containsKey(handle)) return twits.get(handle);
		else {
			Chatter twit = new Chatter(handle); twits.put(handle,twit); return twit;
		}
	}
	
	public void ctell(String msg) { ctell(getName(),msg,false); }
	public void ctell(String sender, String msg, boolean echo) {
		ObjectNode twit = mapper.createObjectNode();
		twit.set("chatter",getTwit(sender).chatterToJSON());
		twit.put("msg", msg);
		for (Connection conn : serv.getAllConnections(true)) conn.tell("ctell",twit);
		if (echo) tch(sender + ": " + msg);
	}
	
	@Override
	public void tell(String handle, String msg) {
		log("TELL -> " + handle + " : " + msg);
		super.tell(handle,msg);
		for (Connection conn : getConns(handle)) conn.tell("new_tell",msg);
	}
	
	@Override
	public void handleMsg(String chan, String sender, String msg, boolean whisper) { //TODO: is this needed?
		if (!whisper) ctell(sender,msg,false);
		else {
			String[] tokens = msg.split(" ");
			if (tokens.length == 2 && tokens[0].equalsIgnoreCase("TOP")) {
				tell(sender,top(tokens[1],10).toString());
			}
		}
	}
	
	@Override
	public void adminCmd(String cmd, boolean whisper) {
		String[] tokens = cmd.split(" ");
		switch (tokens.length) {
			case 1:
				if (tokens[0].equalsIgnoreCase("NEWGAME")) followTVGame();
				else if (tokens[0].equalsIgnoreCase("RAFFLE")) tch(raffle(true) + " wins the raffle!"); //TODO: tch?!
				break;
			case 2:
				if (tokens[0].equalsIgnoreCase("GAME")) {
					followTVGame(tokens[1]);
					ctell("Following: " + tokens[1]);
				}
				else if (tokens[0].equalsIgnoreCase("GAMETYPE")) {
					gameType = tokens[1];
					ctell("Game type: " + gameType);
					for (GameClient.GameThread thread : tv_client.getGames()) thread.getSock().end();
				}
				break;
		}
	}
	
	@Override
	public void gameMsg(GameSock sock, String message) {
		try {
			JsonNode node = mapper.readTree(message);
			JsonNode type = node.get("t");
			JsonNode data = node.get("d");
			//if (data != null) log(message);
			if (type != null) switch (type.textValue()) {
				case "b": 
					for (int i=0; i < data.size(); i++) gameMsg(sock,data.get(i).toString());
					break;
				case "move": 
					newMove(data); 
					lastMoveTime = System.currentTimeMillis();
					break; 
				case "end":
					sock.end();
					break;
				default: 
					if (lastMoveTime < System.currentTimeMillis() - TIMEOUT) {
						log("TIMEOUT!"); sock.end();
					}
					break;
			}
		} 
		catch (JsonProcessingException e) { e.printStackTrace(); } 
		catch (IOException e) { e.printStackTrace(); }
	}
	
	@Override
	public void finished(GameSock sock) {
		log("Game finished!");
		followTVGame();
	}
	
	private boolean handleGeneralCmd(Connection conn, String cmd, JsonNode data) {
		if (cmd.equalsIgnoreCase("UPDATE")) updateAll(conn);
		else if (cmd.equalsIgnoreCase("FINGER")) {
			conn.tell("finger",finger(conn.getHandle()));
		}
		else if (cmd.equalsIgnoreCase("TOP")) {
			conn.tell("top",top(data.asText(),10));
		}
		else if (cmd.equalsIgnoreCase("TELL_CHAN")) {
			ctell(conn.getHandle(),data.asText(),true);
		}
		else if (cmd.equalsIgnoreCase("OBS")) {
			for (BingoPlayer p : bingoers.values()) p.removeObs(conn);
			BingoPlayer o = getBingoer(data.asText());	
			if (o != null) { o.addObs(conn); o.listener.updateCard(o); }
		}
		else if (cmd.equalsIgnoreCase("UNOBS")) {
			for (BingoPlayer p : bingoers.values()) p.removeObs(conn);
		}
		else return false;
		return true;
	}
	
	private boolean handleBingoCmd(Connection conn, BingoPlayer bingoer, String cmd) {
		if (cmd.equalsIgnoreCase("NEW")) {
			if (bingoer == null) newBingoPlayer(conn.getHandle(),1);
			else bingoer.newCard();
		}
		else if (cmd.equalsIgnoreCase("BINGO")) { 
			if (bingoer == null) {
				ctell(conn.getHandle() + " isn't even playing!  What a doofus.");
			}
			else if (bingoer.checkBingo()) winner_bingo(bingoer);
			else {	
				ctell(conn.getHandle() + " made a false bingo claim! Sad.");
				if (bingoer != null) bingoer.newCard();
			}
		}
		else return false;
		return true;
	}
	
	@Override
	public void newMsg(Connection conn, String msg) {
		JsonNode node = null;
		try { node = mapper.readTree(msg); } 
		catch (JsonProcessingException e) { e.printStackTrace(); return; } 
		catch (IOException e) { e.printStackTrace(); return; }
		String cmd = node.get("cmd").asText();
		JsonNode data = node.get("data");
		log("CMD: " + cmd + ", from " + conn.getHandle());
		//log("general cmd...");
		if (handleGeneralCmd(conn,cmd,data)) return;
		//log("bingo cmd...");
		if (handleBingoCmd(conn,getBingoer(conn.getHandle()),cmd)) return;
		log("Error: unknown command: " + cmd);
	}
	
	@Override
	public void loggedIn(Connection conn) {
		updateAll(conn);
	}

	@Override
	public void disconnected(Connection conn) {
		//for (Player player : players.values()) player.removeObs(conn);
	}
	
	@Override
	public void updateCard(BingoPlayer bingoer) {
		for (Connection c : getConns(bingoer.handle)) c.tell("card", bingoer.toJSON());
		for (Connection c : bingoer.observers) c.tell("card", bingoer.toJSON());
	}
	
	public void buyCard(String handle, int fee) {
		if (!newMongoPlayer(handle)) {
			playData.updateOne(eq("name",handle),inc("games",1));
			playData.updateOne(eq("name",handle),inc("gold",-fee));
		}
		for (Connection conn : getConns(handle)) conn.tell("new_tell","You have bought a new card.");
	}
	
	public boolean newMongoPlayer(String name) {
		if (playData != null && playData.find(eq("name", name)).first() == null) {
			playData.insertOne(new Document()
				.append("name", name) //to lowercase?
				.append("games", 1)
				.append("gold", 100)
				.append("gold_won", 0)
				.append("tickets", 1)
				.append("tickets_won", 0));
			return true;
		}
		else return false;
	}
	
	public String raffle(boolean mock) {
		FindIterable<Document> docs = playData.find();
		ArrayList<String> playBag = new ArrayList<String>();
		for (Document doc : docs) {
			String name = doc.getString("name");
			int tickets = doc.getInteger("tickets");
			for (int i=0; i < tickets; i++) {
				playBag.add(name);
			}
			if (!mock) {
				playData.updateOne(eq("name",name),set("tickets",0));
				playData.updateOne(eq("name",name),set("gold",100));
			}
		}
		if (playBag.size() > 0) {
			Collections.shuffle(playBag);
			return playBag.get(0);
		}
		else return null;
	}
}

