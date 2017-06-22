package org.chernovia.net.games.bingochess.augh;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Vector;
import org.chernovia.lib.net.zugserv.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class BingoPlayer extends Chatter {
	
	class BingoCell {
		int x, y;
		boolean ticked;
		
		public BingoCell(int file, int rank) {
			x = file; y = rank; 
			ticked = false;
		}
		
		public void tick() { ticked = true; }
	}
		
	public static final int CARDSIZE = 5;
	public static Vector<Dimension> SQUARE_BAG;
	BingoCell[][] card;
	int fee;
	ArrayList<Connection> observers;
	BingoListener listener;
	ObjectMapper mapper = new ObjectMapper();
	
	public BingoPlayer(String h, int f, BingoListener l) { 
		super(h);
		observers = new ArrayList<Connection>();
		fee = f;
		listener = l;
		handle = h;
		l.buyCard(handle,fee); 
		newCard(); 
	}
	
	public void addObs(Connection c) { if (!observers.contains(c)) observers.add(c); }
	public void removeObs(Connection c) { observers.remove(c); }
	
	public void newCard() {
		card = new BingoCell[CARDSIZE][CARDSIZE];
		Collections.shuffle(SQUARE_BAG);
		int i = 0;
		for (int x=0;x<CARDSIZE;x++)
		for (int y=0;y<CARDSIZE;y++) {
			Dimension d = SQUARE_BAG.get(i++);
			card[x][y] = new BingoCell(d.width,d.height);
		}
		listener.updateCard(this);
	}
	
	public void checkMove(int mx, int my) {
		for (int x=0;x<CARDSIZE;x++)
		for (int y=0;y<CARDSIZE;y++) 
		if (card[x][y].x == mx && card[x][y].y == my) {
			card[x][y].tick();
			listener.updateCard(this);
		}
	}
	
	public boolean checkBingo() {
		for (int x=0;x<CARDSIZE;x++) {
			int y = 0; boolean bingo = true;
			while (bingo && y < CARDSIZE) { bingo = card[x][y++].ticked; }
			if (bingo) return true;
		}
		for (int y=0;y<CARDSIZE;y++) {
			int x = 0; boolean bingo = true;
			while (bingo && x < CARDSIZE) { bingo = card[x++][y].ticked; }
			if (bingo) return true;
		}
		int x = 0; int y = 0; boolean bingo = true;
		while (bingo && x < CARDSIZE) { bingo = card[x++][y++].ticked; }
		if (bingo) return true;
		x = 0; y = CARDSIZE-1; bingo = true;
		while (bingo && x < CARDSIZE) { bingo = card[x++][y--].ticked; }
		if (bingo) return true; else return false;
	}
	
	public JsonNode toJSON() {
		ObjectNode obj = mapper.createObjectNode();
		obj.put("handle", handle);
		obj.put("card_size", CARDSIZE);
		ArrayNode array = mapper.createArrayNode(); 
		for (int x=0;x<CARDSIZE;x++)
		for (int y=0;y<CARDSIZE;y++) {
			ObjectNode cell = mapper.createObjectNode();
			cell.put("square", coordTxt(card[x][y].x,card[x][y].y));
			cell.put("ticked", card[x][y].ticked);
			array.add(cell);
		}
		obj.set("cells", array);
		return obj;
	}
	
	private String coordTxt(int x, int y) {
		return Character.toString((char)('a' + x)) + (8-y);
	}
}
