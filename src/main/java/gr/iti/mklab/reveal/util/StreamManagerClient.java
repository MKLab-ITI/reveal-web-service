package gr.iti.mklab.reveal.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import gr.iti.mklab.sm.feeds.Feed;
import gr.iti.mklab.sm.feeds.GeoFeed;
import gr.iti.mklab.sm.feeds.KeywordsFeed;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.apache.log4j.Logger;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Set;

/**
 * A simple client for handling the simmo stream manager web service
 *
 * @author kandreadou
 */
public class StreamManagerClient {

	private Logger _logger = Logger.getLogger(StreamManagerClient.class);
	
    private String webServiceHost;

    private HttpClient httpClient;

    public StreamManagerClient(String webServiceHost) {
        this.webServiceHost = webServiceHost;
        MultiThreadedHttpConnectionManager cm = new MultiThreadedHttpConnectionManager();
        HttpConnectionManagerParams params = new HttpConnectionManagerParams();
        params.setMaxTotalConnections(100);
        params.setDefaultMaxConnectionsPerHost(25);
        params.setConnectionTimeout(10000);
        cm.setParams(params);
        
        this.httpClient = new HttpClient(cm);
    }

    public void addAllKeywordFeeds(Set<String> keywords, String collection) {
    	
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, -1);
        
        KeywordsFeed feed = new KeywordsFeed();
        feed.addKeywords(new ArrayList<String>(keywords));
        feed.setId("Twitter#" + collection);
        feed.setSinceDate(cal.getTime());
        feed.setSource("Twitter");
        feed.setLabel(collection);
        
        addKeywordsFeed(feed);
        
        KeywordsFeed flickr = new KeywordsFeed();
        flickr.addKeywords(new ArrayList<String>(keywords));
        flickr.setId("Flickr#" + collection);
        flickr.setSinceDate(cal.getTime());
        flickr.setSource("Flickr");
        flickr.setLabel(collection);
        addKeywordsFeed(flickr);
        
        KeywordsFeed instagram = new KeywordsFeed();
        instagram.addKeywords(new ArrayList<String>(keywords));
        instagram.setId("Instagram#" + collection);
        instagram.setSinceDate(cal.getTime());
        instagram.setSource("Instagram");
        instagram.setLabel(collection);
        addKeywordsFeed(instagram);

        KeywordsFeed youtube = new KeywordsFeed();
        youtube.addKeywords(new ArrayList<String>(keywords));
        youtube.setId("Youtube#" + collection);
        youtube.setSinceDate(cal.getTime());
        youtube.setSource("YouTube");
        youtube.setLabel(collection);
        addKeywordsFeed(youtube);
        
        KeywordsFeed googleplus = new KeywordsFeed();
        googleplus.addKeywords(new ArrayList<String>(keywords));
        googleplus.setId("GooglePlus#" + collection);
        googleplus.setSinceDate(cal.getTime());
        googleplus.setSource("GooglePlus");
        googleplus.setLabel(collection);
        addKeywordsFeed(googleplus);
    }

    public void addAllGeoFeeds(double lon_min, double lat_min, double lon_max, double lat_max, String collection) {

        double density = Math.abs((lon_max - lon_min) / 100);
        GeoFeed streetview = new GeoFeed(lon_min, lat_min, lon_max, lat_max, density);
        streetview.setId("StreetView#" + collection);
        streetview.setSource("StreetView");
        streetview.setLabel(collection);
        addGeoFeed(streetview);
        
        GeoFeed panoramio = new GeoFeed(lon_min, lat_min, lon_max, lat_max);
        panoramio.setId("Panoramio#" + collection);
        panoramio.setSource("Panoramio");
        panoramio.setLabel(collection);
        addGeoFeed(panoramio);
        GeoFeed wikimapia = new GeoFeed(lon_min, lat_min, lon_max, lat_max);
        wikimapia.setId("Wikimapia# + collection");
        wikimapia.setSource("Wikimapia");
        wikimapia.setLabel(collection);
        addGeoFeed(wikimapia);
    }

    public void deleteAllFeeds(boolean isGeo, String collection){
        if(isGeo) {
            deleteFeed("StreetView#" + collection);
            deleteFeed("Panoramio#" + collection);
            deleteFeed("Wikimapia#" + collection);
        }
        else {
            deleteFeed("Flickr#" + collection);
            deleteFeed("Twitter#" + collection);
            deleteFeed("Instagram#" + collection);
            deleteFeed("GooglePlus#" + collection);
            deleteFeed("Youtube#" + collection);
        }
    }

    public String addKeywordsFeed(KeywordsFeed kfeed) {
        return addFeed("/sm/api/feeds/addkeywords", kfeed);
    }

    public String addGeoFeed(GeoFeed gfeed) {
        return addFeed("/sm/api/feeds/addgeo", gfeed);
    }

    private String addFeed(String path, Feed feed) {
        Gson gson = new GsonBuilder().create();
        PostMethod queryMethod = null;
        String response = null;
        try {
            queryMethod = new PostMethod(webServiceHost + path);
            queryMethod.setRequestEntity(new StringRequestEntity(gson.toJson(feed), "application/json", "UTF-8"));
            int code = httpClient.executeMethod(queryMethod);
            if (code == 200) {
                response = queryMethod.getResponseBodyAsString();
            }
            else {
            	response = queryMethod.getResponseBodyAsString();
            	_logger.error("Cannot add feed. Response code: " + code + " Response: " + response);
            }
        } catch (Exception e) {
            _logger.error("Exception: " + e.getMessage());
        } finally {
            if (queryMethod != null) {
                queryMethod.releaseConnection();
            }
        }
        return response;
    }

    private String deleteFeed(String id) {
        GetMethod queryMethod = null;
        String response = null;
        try {
            queryMethod = new GetMethod(webServiceHost + "/sm/api/feeds/delete");
            
            id = URLEncoder.encode(id, "UTF-8");
            queryMethod.setQueryString("id=" + id);
            int code = httpClient.executeMethod(queryMethod);
            if (code == 200) {
                response = queryMethod.getResponseBodyAsString();
            }
            else {
            	response = queryMethod.getResponseBodyAsString();
            	_logger.error("Cannot delete feed. Response code: " + code + " Response: " + response);
            }
        } catch (Exception e) {
        	_logger.error("Exception: " + e.getMessage());
        } finally {
            if (queryMethod != null) {
                queryMethod.releaseConnection();
            }
        }
        return response;
    }
}
