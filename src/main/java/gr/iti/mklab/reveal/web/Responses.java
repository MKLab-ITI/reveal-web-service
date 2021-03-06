package gr.iti.mklab.reveal.web;

import gr.iti.mklab.reveal.summarization.RankedImage;
import gr.iti.mklab.simmo.core.items.Image;
import gr.iti.mklab.simmo.core.items.Media;
import gr.iti.mklab.simmo.core.items.Video;
import gr.iti.mklab.simmo.core.jobs.CrawlJob;

import java.net.MalformedURLException;
import java.util.*;

/**
 * Created by kandreadou on 1/9/15.
 */
public class Responses {

    public static class IndexResponse {

        public boolean success = false;

        public String message;

        public IndexResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public IndexResponse() {
            this.success = true;
        }
    }

    public static class SimilarityResponse {

        public double distance;

        public Media item;

        public SimilarityResponse(Media item, double distance) throws MalformedURLException {
            this.item = item;
            this.distance = distance;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SimilarityResponse that = (SimilarityResponse) o;

            if (Double.compare(that.distance, distance) != 0) return false;
            if (item != null ? !item.getId().equals(that.item.getId()) : that.item != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result;
            long temp;
            temp = Double.doubleToLongBits(distance);
            result = (int) (temp ^ (temp >>> 32));
            result = 31 * result + (item != null ? item.hashCode() : 0);
            return result;
        }
    }

    public static class MediaResponse {
        public List<Image> images = new ArrayList<>();

        public List<Video> videos = new ArrayList<>();

        public long numImages;

        public long numVideos;

        public long offset;
    }

    public static class CrawlStatus extends CrawlJob {

        public CrawlStatus(){

        }

        public CrawlStatus(CrawlJob req){
            this.keywords = req.getKeywords();
            this.requestState = req.getState();
            this.collection = req.getCollection();
            this.crawlDataPath = req.getCrawlDataPath();
            this.creationDate = req.getCreationDate();
            this.lastStateChange = req.getLastStateChange();
            this.id = req.getId();
            this.isNew = req.isNew();
        }

        public long numImages;

        public long numIndexedImages;

        public long numVideos;

        public Image image;

        public Video video;

        public long duration;

        public String lastItemInserted;
    }

    public static class SummaryResponse {    
    	
    	public SummaryResponse() {
    		
    	}
    	
    	public SummaryResponse(String status) {
    		this.status = status;
    	}
    	
        private List<RankedImage> summary = new ArrayList<RankedImage>();
        private String status = "running";
		public List<RankedImage> getSummary() {
			return summary;
		}

		public void setSummary(List<RankedImage> summary) {
			this.summary = summary;
		}

		public String getStatus() {
			return status;
		}

		public void setStatus(String status) {
			this.status = status;
		}
    }
}
