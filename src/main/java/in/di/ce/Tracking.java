package in.di.ce;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class Tracking implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private static final Log log = LogFactory.getLog(Tracking.class);

	private int maxFirstCachoSize = 32;
	private int maxCachoSize = 64; 
	private long chunkSize = 1024 * 1024;

	private final Map<String, User> users = new HashMap<String, User>();
	
	/*
	 * videos repository
	 */
	private final ConcurrentMap<String, Video> videos = new ConcurrentHashMap<String, Video>();

	/*
	 * chunks repository
	 * [videoId:[userId:[chunks]]]
	 */
	private final ConcurrentMap<String, Map<String, Set<Integer>>> usersLocalRepoTracking = new ConcurrentHashMap<String, Map<String, Set<Integer>>>();


	public boolean registerVideo(Video video, String userId, List<String> chunks){
		boolean newVideo = videos.putIfAbsent(video.getId(), video) == null; 
		if(newVideo) {
			
			usersLocalRepoTracking.put(video.getId(), new HashMap<String, Set<Integer>>());
			
		}
		List<Integer> ordinals = new ArrayList<Integer>();
		for( int i = 0; i< chunks.size(); i++) {
			ordinals.add(i);
		}
		
		Set<Integer> userChunks = usersLocalRepoTracking.get(video.getId()).get(userId); 
		if (userChunks == null) {
			userChunks = new HashSet<Integer>();
		}
		userChunks.addAll(ordinals);
		usersLocalRepoTracking.get(video.getId()).put(userId, userChunks);

		return newVideo;
	}

	public boolean registerChunks(String videoId, String userId, List<Integer> chunks){

		if(usersLocalRepoTracking.get(videoId) == null){
			log.error("Can't register chunks for video: "+videoId+". there is no such video in database.");
			throw new IllegalStateException("Can't register chunks for video: "+videoId+". there is no such video in database.");
		}

		if (usersLocalRepoTracking.get(videoId).get(userId) == null){
			usersLocalRepoTracking.get(videoId).put(userId, new HashSet<Integer>());
			log.info("adding chunk list for video "+ videoId + " for user " + userId);
		}
		boolean someAdded = usersLocalRepoTracking.get(videoId).get(userId).addAll(chunks);
		if( someAdded ) {
			log.info("Added to index for user "+userId+" for video "+videoId+" the chunks "+chunks);
		} else {
			log.warn("No chunk of "+chunks+" was added to index for user "+userId+" for video "+videoId);
		}
		return someAdded;
	}

	public boolean unregisterChunks(String videoId, String userId, List<Integer> chunks){

		if (!usersLocalRepoTracking.containsKey(videoId) || !usersLocalRepoTracking.get(videoId).containsKey(userId)){ 
			return false;
		}
		return usersLocalRepoTracking.get(videoId).get(userId).removeAll(chunks);
	}

	public Set<Integer>getChunksFrom(String videoId, String userId){

		if (!usersLocalRepoTracking.containsKey(videoId) || !usersLocalRepoTracking.get(videoId).containsKey(userId)){ 
			return null;
		}
		return usersLocalRepoTracking.get(videoId).get(userId);
	}

	public boolean videoExist(String videoId) {
		return videos.containsKey(videoId);
	}

	public Video getVideo(String videoId) {
		return videos.get(videoId);
	}

	public RetrievalPlan plan(String videoId, String userId) {

		Map<String, Set<Integer>> usersWithChunks = usersLocalRepoTracking.get(videoId);

		if(MapUtils.isEmpty(usersWithChunks)){
			log.info("Unable to ellaborate retrieving plan for video "+videoId+" for user "+userId);
			return null;
		}


		UserChunks thisUserChunks = new UserChunks(userId, new ArrayList<Integer>());
		/*
		 * userId:[chunkOrdinals]
		 */
		Map<String, UserChunks> userChunks = new HashMap<String, UserChunks>();

		if(usersWithChunks.containsKey(userId) && CollectionUtils.isNotEmpty(usersWithChunks.get(userId))){
			/*
			 * el usuario tiene chunks de este video
			 */
			thisUserChunks.getChunks().addAll(usersWithChunks.get(userId));
			userChunks.put(userId, thisUserChunks);
		}

		Video video = this.getVideo(videoId);

		Set<UserChunks> userChunksList = new HashSet<UserChunks>();
		
		List<String> usersToRequest = usersToRequest(usersWithChunks, userId);

		for(int i = 0; i<video.getChunks().size(); /*i++*/) {
			if(thisUserChunks.getChunks().contains(i)) {
				UserChunks uc = segmentFrom(i, thisUserChunks);
				userChunksList.add(uc);
				i+=uc.getChunks().size();
			} else {
				UserChunks poorUserChunks = getShorterUserChunksFrom(i, usersWithChunks, usersToRequest);
				
				if(poorUserChunks == null){
					/*
					 * no chunks for this set of users, call again but with all users
					 */
					poorUserChunks = getShorterUserChunksFrom(i, usersWithChunks, usersToRequest(usersWithChunks, userId));
					
					if(poorUserChunks == null){
						throw new IllegalStateException("Unable to complete retrieval plan for video: "+videoId+" for user : "+userId +" - Users requested: "+usersToRequest);
					}
				}
				userChunksList.add(poorUserChunks);
				i+=poorUserChunks.getChunks().size();
			}
		}

		int total = 0;
		for(UserChunks uc : userChunksList){
			total+=uc.getChunks().size();
		}
		if(total != video.getChunks().size()) {
			log.error("Unable to ellaborate retrieving plan for video "+videoId+" for user "+userId +" - not enough sources available");
			throw new IllegalStateException("Unable to ellaborate retrieving plan for video "+videoId+" for user "+userId+" - not enough sources available");
		}
		return retrievalPlanFor(video, cachosFrom(userChunksList, video));

	}
	
	private RetrievalPlan retrievalPlanFor(Video video,
			List<UserCacho> userCachos) {
		RetrievalPlan rp = new RetrievalPlan(video, userCachos); 
		return rp;
	}

	private List<UserCacho> cachosFrom(Set<UserChunks> chunks, Video video) {

		List<UserCacho> result = new ArrayList<UserCacho>();
		for(UserChunks uc : chunks) {
			result.add(new UserCacho(this.loadUser(uc.getUserId()), cachoFromUserChunks(uc, video)));
		}
		return result;
	}

	private Cacho cachoFromUserChunks(UserChunks uc, Video video) {
		
		long from =  uc.getChunks().get(0) * chunkSize;
		long lenght = uc.getChunks().size() * chunkSize;
		
		boolean lastCacho = (uc.getChunks().get(uc.getChunks().size()-1)) == video.getChunks().size()-1;
		
		if(lastCacho){
			long diff  = chunkSize - (video.getLenght() % chunkSize);
			lenght-= diff;
		}
		Cacho cacho = new Cacho(from, lenght);
		
		return cacho;
	}

	public boolean newUser(User newUser) {
		if(!users.containsKey(newUser.getId() ) ) {
			users.put(newUser.getId(), newUser);
			return true;
		}
		return false;
	}
	
	private User loadUser(String userId) {
		User user = this.users.get(userId);
		return user;
	}

	private List<String> usersToRequest(
			Map<String, Set<Integer>> usersWithChunks, String userId) {
		List<String> usersToRequest = new ArrayList<String>(usersWithChunks.keySet());
		usersToRequest.remove(userId);
		return usersToRequest;
	}

	private UserChunks getShorterUserChunksFrom(int i, Map<String, Set<Integer>> usersWithChunks, List<String> usersToRequest) {

		int max = i == 0 ? maxFirstCachoSize : maxCachoSize;
		String user = null;
		UserChunks result = null;
		
		for(String userId : usersToRequest) {
			if(usersWithChunks.get(userId).contains(i)) {
				result = new UserChunks(userId);
				int current = i;
				while(true) {
					if(!usersWithChunks.get(userId).contains(current) || current == max) {
						usersToRequest.remove(user);
						return result;
					}
					result.addChunk(current);
					current++;
				}
			}
		}
		throw new IllegalStateException("unable to build cacho");
	}

	private UserChunks segmentFrom(int from, UserChunks thisUserChunks) {
		UserChunks result = new UserChunks(thisUserChunks.getUserId(), new ArrayList<Integer>());
		int current = from;
		while(true) {
			if(!thisUserChunks.getChunks().contains(current)) {
				break;
			}
			result.getChunks().add(current);
			current++;
		}
		return result;
	}

	public String getVideoIdFromFilename(String fileName) {

		for(Map.Entry<String, Video> entry : videos.entrySet()){
			if(entry.getValue().getFileName().equals(fileName)){
				return entry.getKey();
			}
		}
		return null;
	}

	public boolean userExist(String userId) {
		return users.containsKey(userId);
	}


}
