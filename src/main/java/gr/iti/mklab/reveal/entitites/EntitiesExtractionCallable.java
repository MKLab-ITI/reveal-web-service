package gr.iti.mklab.reveal.entitites;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import gr.iti.mklab.reveal.text.NameThatEntity;
import gr.iti.mklab.reveal.text.TextPreprocessing;
import gr.iti.mklab.simmo.core.annotations.NamedEntity;
import gr.iti.mklab.simmo.core.items.Image;
import gr.iti.mklab.simmo.core.morphia.MediaDAO;
import gr.iti.mklab.simmo.core.morphia.MorphiaManager;
import org.mongodb.morphia.dao.BasicDAO;
import org.mongodb.morphia.dao.DAO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by kandreadou on 6/4/15.
 */
public class EntitiesExtractionCallable implements Callable<List<NamedEntity>> {

    private String collection;
    private NameThatEntity nte;

    /**
     * A Multiset to store frequencies for named entities
     */
    public Multiset<String> ENTITIES_MULTISET = ConcurrentHashMultiset.create();
    /**
     * A HashMap to store entity strings and tokens
     */
    private Map<String, String> ENTITIES_MAP = new HashMap<>();

    private DAO<NamedEntity, String> entitiesDAO;

    public EntitiesExtractionCallable(NameThatEntity nte, String collection) {
        this.collection = collection;
        this.nte = nte;
        entitiesDAO = new BasicDAO<>(NamedEntity.class, MorphiaManager.getMongoClient(), MorphiaManager.getMorphia(), MorphiaManager.getDB(collection).getName());
    }

    @Override
    public List<NamedEntity> call() throws Exception {
        MediaDAO<Image> imageDAO = new MediaDAO<>(Image.class, collection);
        List<Image> list = imageDAO.getItems(0, 10);
        for (Image im : list) {
            TextPreprocessing textPre = new TextPreprocessing(im.getAlternateText() + " " + im.getTitle() + " " + im.getDescription());
            ArrayList<String> cleanedText = textPre.getCleanedSentences();
            List<NamedEntity> entities = nte.tagIt(cleanedText);
            for (NamedEntity ne : entities) {
                im.addAnnotation(ne);
                if (ENTITIES_MULTISET.add(ne.getToken().toLowerCase()))
                    ENTITIES_MAP.put(ne.getToken().toLowerCase(), ne.getType());
            }
        }
        Iterable<Multiset.Entry<String>> cases =
                Multisets.copyHighestCountFirst(ENTITIES_MULTISET).entrySet();
        for (Multiset.Entry<String> s : cases) {
            if (s.getCount() < 10)
                break;
            NamedEntity y = new NamedEntity(s.getElement(), ENTITIES_MAP.get(s.getElement()), s.getCount());
            entitiesDAO.save(y);
            System.out.println(s.getElement() + " count " + s.getCount());

        }

        return null;
    }

    public static void main(String[] args) throws Exception {
        //Configuration.load("local.properties");
        MorphiaManager.setup("127.0.0.1");
        NameThatEntity nte = new NameThatEntity();
        nte.initPipeline();
        ExecutorService clusteringExecutor = Executors.newSingleThreadExecutor();
        clusteringExecutor.submit(new EntitiesExtractionCallable(nte, "earthquake")).get();
        clusteringExecutor.shutdown();
        MorphiaManager.tearDown();
    }

}