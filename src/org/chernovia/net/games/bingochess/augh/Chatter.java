package org.chernovia.net.games.bingochess.augh;

import java.awt.Color;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;


public class Chatter {
	protected String handle;
	Color color;
	ObjectMapper mapper = new ObjectMapper();
	
	public Chatter(String h) {
		handle = h;
		float brightness = ((float)Math.random()/2) + .5f;
		color = Color.getHSBColor((float)Math.random(),(float)Math.random(),brightness);
	}
	
	public JsonNode chatterToJSON() {
		ObjectNode obj = mapper.createObjectNode();
		obj.put("handle", handle);
		obj.put("color", 
		String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()));
		return obj;
	}
}
