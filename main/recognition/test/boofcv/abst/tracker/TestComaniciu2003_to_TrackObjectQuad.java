package boofcv.abst.tracker;

import boofcv.factory.tracker.FactoryTrackerObjectQuad;
import boofcv.struct.image.ImageType;
import boofcv.struct.image.ImageUInt8;
import boofcv.struct.image.MultiSpectral;

/**
 * @author Peter Abeles
 */
public class TestComaniciu2003_to_TrackObjectQuad extends ColorTrackerObjectRectangleTests {

	public TestComaniciu2003_to_TrackObjectQuad() {
		super(true);
		tolTranslateSmall = 0.05;
		tolScale = 0.1;
	}

	@Override
	public TrackerObjectQuad<MultiSpectral<ImageUInt8>> create(ImageType<MultiSpectral<ImageUInt8>> imageType) {
		ConfigComaniciu2003<MultiSpectral<ImageUInt8>> config =
				new ConfigComaniciu2003<MultiSpectral<ImageUInt8>>(imageType);

		config.scaleChange = 0.1f;

		return FactoryTrackerObjectQuad.meanShiftComaniciu2003(config);
	}

	@Override
	public void translation_large() {
		// Not designed to handle
	}
}
