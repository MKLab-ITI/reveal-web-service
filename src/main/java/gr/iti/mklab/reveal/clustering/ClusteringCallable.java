package gr.iti.mklab.reveal.clustering;

import com.aliasi.tokenizer.TokenizerFactory;

import gr.iti.mklab.reveal.util.Configuration;
import gr.iti.mklab.reveal.visual.VisualIndexClient;
import gr.iti.mklab.simmo.core.annotations.Clustered;
import gr.iti.mklab.simmo.core.items.Image;
import gr.iti.mklab.simmo.core.items.Media;
import gr.iti.mklab.simmo.core.items.Video;
import gr.iti.mklab.simmo.core.morphia.MediaDAO;
import gr.iti.mklab.simmo.core.morphia.MorphiaManager;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.math3.ml.clustering.Cluster;
import org.apache.log4j.Logger;
import org.mongodb.morphia.dao.BasicDAO;
import org.mongodb.morphia.dao.DAO;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * A clustering callable which clusters the given number of images and videos from
 * the specified collection using the supplied configuration options.
 *
 * @author kandreadou
 */
public class ClusteringCallable implements Callable<List<Cluster<ClusterableMedia>>> {

	private Logger _logger = Logger.getLogger(ClusteringCallable.class);
			
    private String collection;
    private int count;
    private double eps;
    private int minpoints;

    public ClusteringCallable(String collection, int count, double eps, int minpoints) {
        this.collection = collection;
        this.count = count;
        this.eps = eps;
        this.minpoints = minpoints;
    }

    @Override
    public List<Cluster<ClusterableMedia>> call() throws Exception {
    	_logger.info("Run DBSCAN for " + collection + ", eps=" + eps + ", minpoints=" + minpoints + ", count= " + count);
        
        String indexServiceHost = "http://" + Configuration.INDEX_SERVICE_HOST + ":8080/VisualIndexService";
		VisualIndexClient vIndexClient = new VisualIndexClient(indexServiceHost, collection);  
		
        TokenizerFactory tokFactory = new NormalizedTokenizerFactory();
        //First get the existing clusters for this collection
        DAO<gr.iti.mklab.simmo.core.cluster.Cluster, String> clusterDAO = new BasicDAO<>(gr.iti.mklab.simmo.core.cluster.Cluster.class, MorphiaManager.getMongoClient(), MorphiaManager.getMorphia(), MorphiaManager.getDB(collection).getName());
        List<gr.iti.mklab.simmo.core.cluster.Cluster> clustersINDB = clusterDAO.find().asList();
        List<org.apache.commons.math3.ml.clustering.Cluster<ClusterableMedia>> existingClusters = new ArrayList<>();
        clustersINDB.stream().forEach(dbCluster -> {
            org.apache.commons.math3.ml.clustering.Cluster<ClusterableMedia> item = new org.apache.commons.math3.ml.clustering.Cluster<>();
            dbCluster.getMembers().stream().forEach(mediaItem -> {
                String id = ((Media)mediaItem).getId();
				Double[] vector = vIndexClient.getVector(id);
				
				item.addPoint(new ClusterableMedia((Media) mediaItem, ArrayUtils.toPrimitive(vector)));
            });
            existingClusters.add(item);
        });
        
        List<ClusterableMedia> list = new ArrayList<>();
        //images
        MediaDAO<Image> imageDAO = new MediaDAO<>(Image.class, collection);

        List<Image> images = imageDAO.getIndexedNotClustered(count);
        _logger.info("Indexed not clustered images " + images.size());
        images.stream().forEach(i -> {
            Double[] vector = vIndexClient.getVector(i.getId());
            if (vector != null && vector.length == 1024) {
                list.add(new ClusterableMedia(i, ArrayUtils.toPrimitive(vector)));
            }
        });
        
        //videos
        MediaDAO<Video> videoDAO = new MediaDAO<>(Video.class, collection);
        List<Video> videos = videoDAO.getIndexedNotClustered(count);
        System.out.println("Indexed not clustered videos "+videos.size());
        videos.stream().forEach(i -> {
            Double[] vector = vIndexClient.getVector(i.getId());
            if (vector != null && vector.length == 1024) {
                list.add(new ClusterableMedia(i, ArrayUtils.toPrimitive(vector)));
        	}
        });
        
        DBSCANClusterer<ClusterableMedia> clusterer = new DBSCANClusterer<ClusterableMedia>(eps, minpoints);
        List<org.apache.commons.math3.ml.clustering.Cluster<ClusterableMedia>> centroids = clusterer.clusterIncremental(list, existingClusters);
        clusterDAO.deleteByQuery(clusterDAO.createQuery());
        _logger.info("DBSCAN found " + centroids.size() + " clusters for " + collection);
        for (org.apache.commons.math3.ml.clustering.Cluster<ClusterableMedia> c : centroids) {
            List<Media> initial = new ArrayList<>();
            gr.iti.mklab.simmo.core.cluster.Cluster cluster = new gr.iti.mklab.simmo.core.cluster.Cluster();
            cluster.setSize(c.getPoints().size());
            c.getPoints().stream().forEach(clusterable -> {
                Media media = clusterable.item;
                media.addAnnotation(new Clustered(cluster.getId()));
                initial.add(media);
                if (media instanceof Image) {
                    imageDAO.save((Image) media);
                } else {
                    videoDAO.save((Video) media);
                }
            });
            
            System.out.println("Initial size " + initial.size());
            List<Media> filteredNomralized = TextDeduplication.filterNormalizedDuplicates(initial, tokFactory);
            System.out.println("After normalization size " + filteredNomralized.size());
            List<Media> filteredJaccard = TextDeduplication.filterMediaJaccard(filteredNomralized, tokFactory, 0.5);
            System.out.println("After jaccard size " + filteredJaccard.size());
            filteredJaccard.stream().forEach(m -> cluster.addMember(m));
            cluster.setSize(filteredJaccard.size());
            if (cluster.getSize() < 100) {
                clusterDAO.save(cluster);
            }
        }
        return centroids;
    }

  
}
