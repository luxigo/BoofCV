package boofcv.alg.sfm;

import boofcv.abst.feature.tracker.KeyFramePointTracker;
import boofcv.abst.geo.RefinePnP;
import boofcv.numerics.fitting.modelset.ModelMatcher;
import boofcv.struct.geo.Point2D3D;
import boofcv.struct.image.ImageBase;
import georegression.struct.point.Point2D_F64;
import georegression.struct.point.Point3D_F64;
import georegression.struct.se.Se3_F64;
import georegression.transform.se.SePointOps_F64;

import java.util.ArrayList;
import java.util.List;

/**
 * Full 6-DOF visual odometry where a ranging device is assumed for pixels in the primary view and the motion is estimated
 * using a {@link boofcv.abst.geo.Estimate1ofPnP}.  Range is usually estimated using stereo cameras, structured
 * light or time of flight sensors.  New features are added and removed as needed.  Features are removed
 * if they are not part of the inlier feature set for some number of consecutive frames.  New features are detected
 * and added if the inlier set falls below a threshold or every turn.
 *
 * Non-linear refinement is optional and appears to provide a very modest improvement in performance.  It is recommended
 * that motion is estimated using a P3P algorithm, which is the minimal case.  Adding features every frame can be
 * computationally expensive, but having too few features being tracked will degrade accuracy. The algorithm was
 * designed to minimize magic numbers and to be insensitive to small changes in their values.
 *
 * Due to the level of abstraction, it can't take full advantage of the sensors used to estimate 3D feature locations.
 * For example if a stereo camera is used then 3-view geometry can't be used to improve performance.
 *
 * @author Peter Abeles
 */
public class VisOdomPixelDepthPnP<T extends ImageBase> {

	// when the inlier set is less than this number new features are detected
	private int thresholdAdd;

	// discard tracks after they have not been in the inlier set for this many updates in a row
	private int thresholdRetire;

	// tracks features in the image
	private KeyFramePointTracker<T,PointPoseTrack> tracker;
	// used to estimate a feature's 3D position from image range data
	private ImagePixelTo3D pixelTo3D;

	// non-linear refinement of pose estimate
	private RefinePnP refine;

	// estimate the camera motion up to a scale factor from two sets of point correspondences
	private ModelMatcher<Se3_F64, Point2D3D> motionEstimator;

	// location of tracks in the image that are included in the inlie set
	private List<Point2D_F64> inlierPixels = new ArrayList<Point2D_F64>();

	// transform from key frame to world frame
	private Se3_F64 keyToWorld = new Se3_F64();
	// transform from the current camera view to the key frame
	private Se3_F64 currToKey = new Se3_F64();
	// transform from the current camera view to the world frame
	private Se3_F64 currToWorld = new Se3_F64();

	// is this the first camera view being processed?
	private boolean first = true;
	// number of frames processed.
	private long tick;

	/**
	 * Configures magic numbers and estimation algorithms.
	 *
	 * @param thresholdAdd Add new tracks when less than this number are in the inlier set.  Tracker dependent. Set to
	 *                     a value <= 0 to add features every frame.
	 * @param thresholdRetire Discard a track if it is not in the inlier set after this many updates.  Try 2
	 * @param motionEstimator PnP motion estimator.  P3P algorithm is recommended/
	 * @param pixelTo3D Computes the 3D location of pixels.
	 * @param refine Optional algorithm for refining the pose estimate.  Can be null.
	 * @param tracker Point feature tracker.
	 */
	public VisOdomPixelDepthPnP(int thresholdAdd,
								int thresholdRetire ,
								ModelMatcher<Se3_F64, Point2D3D> motionEstimator,
								ImagePixelTo3D pixelTo3D,
								RefinePnP refine ,
								KeyFramePointTracker<T, PointPoseTrack> tracker )
	{
		this.thresholdAdd = thresholdAdd;
		this.thresholdRetire = thresholdRetire;
		this.motionEstimator = motionEstimator;
		this.pixelTo3D = pixelTo3D;
		this.refine = refine;
		this.tracker = tracker;
	}

	/**
	 * Resets the algorithm into its original state
	 */
	public void reset() {
		tracker.reset();
		keyToWorld.reset();
		currToKey.reset();
		first = true;
		tick = 0;
	}

	/**
	 * Estimates the motion given the left camera image.  The latest information required by ImagePixelTo3D
	 * should be passed to the class before invoking this function.
	 *
	 * @param image Camera image.
	 * @return true if successful or false if it failed
	 */
	public boolean process( T image ) {
		tracker.process(image);

		inlierPixels.clear();

		if( first ) {
			addNewTracks();
			first = false;
		} else {
			if( !estimateMotion() ) {
				return false;
			}

			int numDropped = dropUnusedTracks();
			int N = motionEstimator.getMatchSet().size();

			if( thresholdAdd <= 0 || N < thresholdAdd ) {
				changePoseToReference();
				addNewTracks();
			}

//			System.out.println("  num inliers = "+N+"  num dropped "+numDropped+" total "+tracker.getPairs().size());
			tick++;
		}

		return true;
	}


	/**
	 * Updates the relative position of all points so that the current frame is the reference frame.  Mathematically
	 * this is not needed, but should help keep numbers from getting too large.
	 */
	private void changePoseToReference() {
		Se3_F64 keyToCurr = currToKey.invert(null);

		List<PointPoseTrack> all = tracker.getPairs();

		for( PointPoseTrack t : all ) {
			SePointOps_F64.transform(keyToCurr,t.location,t.location);
		}

		concatMotion();
	}

	/**
	 * Removes tracks which have not been included in the inlier set recently
	 *
	 * @return Number of dropped tracks
	 */
	private int dropUnusedTracks() {
		int N = motionEstimator.getMatchSet().size();

		List<PointPoseTrack> all = tracker.getPairs();

		for( int i = 0; i < N; i++ ) {
			PointPoseTrack t = all.get( motionEstimator.getInputIndex(i));
			t.lastInlier = tick;
		}

		List<PointPoseTrack> drop = new ArrayList<PointPoseTrack>();
		for( PointPoseTrack t : all ) {
			if( tick - t.lastInlier >= thresholdRetire ) {
				drop.add(t);
			}
		}

		for( PointPoseTrack t : drop ) {
			tracker.dropTrack(t);
		}
		return drop.size();
	}

	/**
	 * Detects new features and computes their 3D coordinates
	 */
	private void addNewTracks() {
//		System.out.println("----------- Adding new tracks ---------------");

		pixelTo3D.initialize();
		List<PointPoseTrack> spawned = tracker.spawnTracks();

		List<PointPoseTrack> drop = new ArrayList<PointPoseTrack>();

		// estimate 3D coordinate using stereo vision
		for( PointPoseTrack p : spawned ) {
			Point2D_F64 pixel = p.getPixel().p1;

			// discard point if it can't localized
			if( !pixelTo3D.process(pixel.x,pixel.y) || pixelTo3D.getW() == 0 ) {
				drop.add(p);
			} else {
				Point3D_F64 X = p.getLocation();

				double w = pixelTo3D.getW();
				X.set(pixelTo3D.getX() / w, pixelTo3D.getY() / w, pixelTo3D.getZ() / w);

				// translate the point into the key frame
				// SePointOps_F64.transform(currToKey,X,X);
				// not needed since the current frame was just set to be the key frame

				p.lastInlier = tick;
			}
		}

		// drop tracks which couldn't be triangulated
		for( PointPoseTrack p : drop ) {
			tracker.dropTrack(p);
		}
	}

	/**
	 * Estimates motion from the set of tracks and their 3D location
	 *
	 * @return true if successful.
	 */
	private boolean estimateMotion() {

		List<Point2D3D> obs = new ArrayList<Point2D3D>();

		for( PointPoseTrack t : tracker.getPairs() ) {
			Point2D3D p = new Point2D3D();

			p.location = t.getLocation();
			p.observation = t.p2;

			obs.add(p);
		}

		// estimate the motion up to a scale factor in translation
		if( !motionEstimator.process( obs ) )
			return false;

		Se3_F64 keyToCurr;

		if( refine != null ) {
			keyToCurr = new Se3_F64();
			refine.process(motionEstimator.getModel(),motionEstimator.getMatchSet(),keyToCurr);
//				return false;
		} else {
			keyToCurr = motionEstimator.getModel();
		}

		keyToCurr.invert(currToKey);

		int N = motionEstimator.getMatchSet().size();
		for( int i = 0; i < N; i++ ) {
			int index = motionEstimator.getInputIndex(i);
			PointPoseTrack t = tracker.getPairs().get(index);

			inlierPixels.add( t.getPixel().p2);
		}

		return true;
	}

	private void concatMotion() {
		Se3_F64 temp = new Se3_F64();
		currToKey.concat(keyToWorld,temp);
		keyToWorld.set(temp);
		currToKey.reset();
	}

	public Se3_F64 getCurrToWorld() {
		currToKey.concat(keyToWorld,currToWorld);
		return currToWorld;
	}

	public KeyFramePointTracker<T, PointPoseTrack> getTracker() {
		return tracker;
	}

	public ModelMatcher<Se3_F64, Point2D3D> getMotionEstimator() {
		return motionEstimator;
	}

	public List<Point2D_F64> getInlierPixels() {
		return inlierPixels;
	}
}
