package com.qux.bus;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qux.model.App;
import com.qux.model.Image;
import com.qux.util.DB;
import com.qux.util.Util;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;

public class ImageUploadHandler implements Handler<Message<JsonObject>>{
	
	private final String image_db, folder, app_db;
	
	private final MongoClient client;
	
	private Logger logger = LoggerFactory.getLogger(ImageUploadHandler.class);
	

	
	public ImageUploadHandler(MongoClient client, String folder){
		this.client = client;
		this.image_db = DB.getTable(Image.class);
		this.app_db = DB.getTable(App.class);
		this.folder = folder;
	}




	@Override
	public void handle(Message<JsonObject> event) {
		
		JsonObject msg = event.body();
		
		String appID = msg.getString("appID");
		JsonArray array = msg.getJsonArray("images");
		
		client.findOne(app_db, App.findById(appID), App.getFields("screenSize"), res->{
			
			if(res.succeeded()){
				
				JsonObject app = res.result();
				if(app!=null){
					int maxWidth = getScreenWidth(app);
					processImages(event, array, maxWidth);
				} else {
					this.logger.error("handle() > Could not load app " + appID);
					event.reply(new JsonArray());
				}

		
			} else {
				this.logger.error("handle() > Could not load app " + appID);
				event.reply(new JsonArray());

			}
		});
		
		
		
	}




	private int getScreenWidth(JsonObject app) {
		int maxWidth = Image.MAX_IMAGE_WIDTH;
		if(app.containsKey("screenSize") && app.getJsonObject("screenSize").containsKey("w")){
			maxWidth = app.getJsonObject("screenSize").getInteger("w") * Image.SCALE_FACTOR;
		}
		return maxWidth;
	}




	private void processImages(Message<JsonObject> event, JsonArray array,int maxWidth) {
		JsonArray result = new JsonArray();
		for(int i = 0; i < array.size(); i++){
			
			JsonObject img = array.getJsonObject(i);
				
			String filename = folder + "/" +img.getString("url");
			
			String type = Util.getFileType(filename);
			if("psd".equals(type)){
				convertPSD2JPG(img, filename);
				filename = folder + "/" +img.getString("url");
			}
			
			try{
			
				
				/**
				 * Resample in case it is too big
				 */
				BufferedImage bimg = Util.resample(filename, maxWidth);
				
				if(bimg!= null){
					/**
					 * update json
					 */
					img.put("width", bimg.getWidth());
					img.put("height", bimg.getHeight());
				}
				
			} catch(Exception e){
				logger.error("handle() > could not read image "+ filename + " >> "+  img.getString("name"));
			}
		

			/**
			 * Update object in mongo
			 */
		
			client.save(image_db, img, res->{
				if(!res.succeeded()){
					logger.error("handle() > could not store image");
				}
				result.add(img);
				img.put("id", img.getString("_id"));
				
				/**
				 * If everything was saved, return to rest service
				 */
				if(result.size() == array.size()){
					event.reply(result);
				}
			});
		}
	}




	private String convertPSD2JPG(JsonObject img, String filename) {
		try{
			/**
			 * Init all file names and path like ImageRest
			 */
			String appID = img.getString("appID");
			String imageID = Util.getRandomString();
			String pndImage = imageID + ".jpg";
			String jpgImageFilename = folder +"/" +appID+"/" +  pndImage;
			File psdFile = new File(filename);
			File jpgFile = new File(jpgImageFilename);
			
			logger.info("convertPSD2JPG() > Convert PDS to " + jpgImageFilename); 
			
			
			/**
			 * Load image
			 */
			BufferedImage image = ImageIO.read(psdFile);
			
				
			/**
			 * save as png
			 */
			ImageIO.write(image, "JPEG", jpgFile);
			
			/**
			 * update image object
			 */
			String url = appID + "/" + pndImage;
			img.put("orginal", img.getString("url"));
			img.put("url", url);
			
			/**
			 * delete psd file
			 */
			if(!psdFile.delete()){
				logger.error("convertPSD2JPG() > Could not delete psd file");
			}
			
			filename = pndImage;
		} catch(Exception e){
			logger.error("convertPSD2JPG() > could not convert psd");
		}
		return filename;
	}




	

}
