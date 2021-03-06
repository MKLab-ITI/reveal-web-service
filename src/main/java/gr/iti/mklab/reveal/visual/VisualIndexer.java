package gr.iti.mklab.reveal.visual;

import gr.iti.mklab.reveal.crawler.LinkDetectionRunner;
import gr.iti.mklab.reveal.rabbitmq.RabbitMQPublisher;
import gr.iti.mklab.reveal.util.Configuration;
import gr.iti.mklab.reveal.util.DisturbingDetectorClient;
import gr.iti.mklab.simmo.core.annotations.lowleveldescriptors.LocalDescriptors;
import gr.iti.mklab.simmo.core.documents.Webpage;
import gr.iti.mklab.simmo.core.items.Image;
import gr.iti.mklab.simmo.core.items.Media;
import gr.iti.mklab.simmo.core.items.Video;
import gr.iti.mklab.simmo.core.morphia.MediaDAO;
import gr.iti.mklab.simmo.core.morphia.MorphiaManager;
import gr.iti.mklab.simmo.core.morphia.ObjectDAO;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections15.CollectionUtils;
import org.apache.commons.collections15.Predicate;
import org.apache.log4j.Logger;
import org.mongodb.morphia.query.Query;
import org.mongodb.morphia.query.UpdateOperations;
import org.mongodb.morphia.query.UpdateResults;
/**
 * A runnable that indexes all non indexed images found in the specified collection
 * and waits if there are no new images or videos to index
 *
 * @author kandreadou
 */
public class VisualIndexer implements Runnable {
    
    private final static Logger LOGGER = Logger.getLogger(VisualIndexer.class);
    		
    private final static int INDEXING_PERIOD = 60 * 1000;	// 60 seconds delay if no new media are available
    private final static int STEP = 200;					// number of media to be indexed per batch 
    
    private RabbitMQPublisher _publisher;
    
    private MediaDAO<Image> imageDAO;
    private MediaDAO<Video> videoDAO;
    private ObjectDAO<Webpage> pageDAO;
    
    private LocalDescriptors ld = new LocalDescriptors();
    
    private boolean isRunning = true;
    
    private final String collection;
    
    private ExecutorService executor;

	private int NUM_THREADS = 10;

	private VisualIndexClient vIndexClient;
	
    public VisualIndexer(String collection) {
        
    	LOGGER.info("Creating IndexingRunner for collection " + collection);
        this.collection = collection;
        
        if (Configuration.PUBLISH_RABBITMQ) {
            _publisher = new RabbitMQPublisher("localhost", collection);
        }
        DisturbingDetectorClient.initialize(Configuration.DISTURBING_DETECTOR_HOST);
        
        String indexServiceHost = "http://" + Configuration.INDEX_SERVICE_HOST + ":8080/VisualIndexService";
        vIndexClient = new VisualIndexClient(indexServiceHost, collection);    
        
        imageDAO = new MediaDAO<>(Image.class, collection);
        videoDAO = new MediaDAO<>(Video.class, collection);
        pageDAO = new ObjectDAO<>(Webpage.class, collection);
        
        ld.setDescriptorType(LocalDescriptors.DESCRIPTOR_TYPE.SURF);
        ld.setFeatureEncoding(LocalDescriptors.FEATURE_ENCODING.Vlad);
        ld.setNumberOfFeatures(1024);
        ld.setFeatureEncodingLibrary("multimedia-indexing");
        
        executor = Executors.newFixedThreadPool(NUM_THREADS);
		
    }

    @Override
    public void run() {
        LOGGER.info("Indexing runner run for " + collection);
        try {
			vIndexClient.createCollection();
		} catch (IOException ex) {
			LOGGER.info("Cannot create index for collection " + collection, ex);
			isRunning = false;
			return;
		} 
        
        try {
        	imageDAO.count();
        	videoDAO.count();
        } catch (Exception ex) {
			LOGGER.info("MongoDB DAOs are closed for " + collection, ex);
			isRunning = false;
			return;
		}
        
		Set<String> processed = new HashSet<String>();	
        while (isRunning) {
            try {            	
                List<Media> mediaToIndex = new ArrayList<Media>();
                mediaToIndex.addAll(imageDAO.getNotVIndexed(STEP));
                mediaToIndex.addAll(videoDAO.getNotVIndexed(STEP));
                
                LOGGER.info("Media list size before filtering for " + collection + " is " + mediaToIndex.size());
				CollectionUtils.filter(mediaToIndex, new Predicate<Media>() {
					@Override
					public boolean evaluate(Media m) {
						return processed.contains(m.getId()) ? false : true;
					}
				});
                LOGGER.info("Media list size after filtering for " + collection + " is " + mediaToIndex.size());
            
                if (mediaToIndex.isEmpty()) {
                    try {
                    	if(!isRunning) {
                    		break;
                    	}
                    	
                        Thread.sleep(INDEXING_PERIOD);
                    } catch (InterruptedException ie) {
                    	LOGGER.info("Indexing runner " + collection + " interrupted.");
                    }
                } 
                else {
                	Map<String, Media> unindexedMedia = new HashMap<String, Media>();
                	
        			for (Media media : mediaToIndex) {
        				processed.add(media.getId());
            			unindexedMedia.put(media.getId(), media);
        			}
        			
        			List<Future<IndexingResult>> futures = submitTasks(mediaToIndex);
        			Map<String, Media> indexedMedia = consume(futures);
        			
        			for(String mediaId : indexedMedia.keySet()) {
        				unindexedMedia.remove(mediaId);
        			}
        			
        			LOGGER.info(unindexedMedia.size() + " media out of " + mediaToIndex.size() + " failed to be indexed!");
        			LOGGER.info(collection + " indexing statistics: " + IndexingCallable.stats());
        			
        			for(Media failedMedia : unindexedMedia.values()) {
        				try {
        					deleteMedia(failedMedia);
        				}
        				catch(Exception e) {
        					LOGGER.error("Exception during deletion of " + failedMedia.getId(), e);
        				}
        			}	
                }
                
                if (_publisher != null) {
                    _publisher.close();
                }
                
            } 
            catch (IllegalStateException ex) {
            	// This never should happen
               LOGGER.error("IllegalStateException: " + ex.getMessage());
                try {
                    imageDAO = new MediaDAO<>(Image.class, collection);
                    videoDAO = new MediaDAO<>(Video.class, collection);
                    pageDAO = new ObjectDAO<>(Webpage.class, collection);
                }
                catch(Exception e) {
                    LOGGER.error("Could not re-create collections. Exception: " + e.getMessage());
                }
            }
            catch(Exception other) {
                LOGGER.error("Exception " + other.getMessage() + " for " + collection);
            }
            
            LOGGER.info(processed.size() + " media items processed so far for " + collection);
        }
    }
    
    public boolean isRunning() {
    	return isRunning;
    }
    
    public void stop() {
        isRunning = false;
        executor.shutdown(); // Disable new tasks from being submitted
		try {
			// Wait a while for existing tasks to terminate
			if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
				executor.shutdownNow(); // Cancel currently executing tasks
				// Wait a while for tasks to respond to being cancelled
				if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
					LOGGER.error("Visual indexer pool did not terminate for " + collection);
				}
			}
		} catch (InterruptedException ie) {
			// (Re-)Cancel if current thread also interrupted
			executor.shutdownNow();
			// Preserve interrupt status
			Thread.currentThread().interrupt();
		}
    }
    
    private void deleteMedia(Media media) {
    	if(media instanceof Image) {
        	imageDAO.delete((Image)media);
        	pageDAO.deleteById(media.getId());
        	if (LinkDetectionRunner.LAST_POSITION > 0) {
        		LinkDetectionRunner.LAST_POSITION--;
        	}
		}
        else if(media instanceof Video) {
			videoDAO.delete((Video)media);
		}
    	else {
    		LOGGER.info("Unknown instance for " + media.getId());
    	}
    }

	public List<Future<IndexingResult>> submitTasks(List<Media> media) throws InterruptedException {
		List<Callable<IndexingResult>> tasks = new ArrayList<Callable<IndexingResult>>();
		for(Media m : media) {
			Callable<IndexingResult> task = new IndexingCallable(m, collection);	
			tasks.add(task);
		}
		return executor.invokeAll(tasks);
	}
	

	public Map<String, Media> consume(List<Future<IndexingResult>> futures) {
		
		Map<String, Media> indexedMedia = new HashMap<String, Media>();
		ArrayDeque<Future<IndexingResult>> queue = new ArrayDeque<Future<IndexingResult>>(futures);
		while (!queue.isEmpty()) {	
			Future<IndexingResult> future = queue.poll();
			if(future.isCancelled()) {
				continue;
			}
				
			if(!future.isDone()) {
				queue.offer(future);
			}

			try {
				IndexingResult result = future.get();	
				if(result.vector != null && result.vector.length > 0) {
					Media media = result.media;		
					boolean indexed = vIndexClient.index(media.getId(), result.vector);
					if (indexed) {
						media.addAnnotation(ld);
						if(media instanceof Image) {
							Query<Image> q = imageDAO.createQuery().filter("url", media.getUrl());
							UpdateOperations<Image> ops = imageDAO.createUpdateOperations().add("annotations", ld);
							UpdateResults r = imageDAO.update(q, ops); 
							if(!r.getUpdatedExisting()) {
								LOGGER.error("Visual Indexer failed to update media " + media.getId() + " in mongodb for " + collection);
								continue;
							}
						}
						else if(media instanceof Video) {
							Query<Video> q = videoDAO.createQuery().filter("url", media.getUrl());
							UpdateOperations<Video> ops = videoDAO.createUpdateOperations().add("annotations", ld);
							UpdateResults r = videoDAO.update(q, ops);
							if(!r.getUpdatedExisting()) {
								LOGGER.error("Visual Indexer failed to update media " + media.getId() + " in mongodb for " + collection);
								continue;
							}
						}
						else {
							LOGGER.error("Unknown instance of " + media.getId());
							continue;
						}
						
						indexedMedia.put(media.getId(), media);
						
						if (_publisher != null) {
							_publisher.publish(MorphiaManager.getMorphia().toDBObject(media).toString());
						}
					} 
					else {
						LOGGER.info("Failed to index" + result.media.getId() + ". This will be deleted!");
					}
				}
				else {
					LOGGER.debug("Vector for " + result.media.getId() + " is empty. This will be deleted!");
				}
			} catch (InterruptedException e) {
				LOGGER.error(e.getMessage());
			} catch (ExecutionException e) {
				LOGGER.error(e.getMessage());
			}		
		}
		
		return indexedMedia;
	}	
}
