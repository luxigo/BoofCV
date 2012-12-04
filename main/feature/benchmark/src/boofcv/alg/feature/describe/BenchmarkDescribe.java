/*
 * Copyright (c) 2011-2012, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.feature.describe;

import boofcv.abst.feature.describe.DescribeRegionPoint;
import boofcv.alg.feature.describe.brief.FactoryBriefDefinition;
import boofcv.alg.filter.derivative.GImageDerivativeOps;
import boofcv.alg.misc.GImageMiscOps;
import boofcv.alg.transform.ii.GIntegralImageOps;
import boofcv.core.image.GeneralizedImageOps;
import boofcv.factory.feature.describe.FactoryDescribePointAlgs;
import boofcv.factory.feature.describe.FactoryDescribeRegionPoint;
import boofcv.factory.filter.blur.FactoryBlurFilter;
import boofcv.misc.Performer;
import boofcv.misc.PerformerBase;
import boofcv.misc.ProfileOperation;
import boofcv.struct.feature.TupleDesc;
import boofcv.struct.feature.TupleDesc_B;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageSingleBand;
import georegression.struct.point.Point2D_I32;

import java.util.Random;


/**
 * @author Peter Abeles
 */
@SuppressWarnings("unchecked")
public class BenchmarkDescribe<I extends ImageSingleBand, D extends ImageSingleBand, II extends ImageSingleBand> {

	static final long TEST_TIME = 1000;
	static Random rand = new Random(234234);
	static int NUM_POINTS = 512;

	final static int width = 640;
	final static int height = 480;

	I image;

	Point2D_I32 pts[];
	double scales[];
	double yaws[];

	Class<I> imageType;
	Class<D> derivType;
	Class<II> integralType;

	public BenchmarkDescribe( Class<I> imageType ) {

		this.imageType = imageType;

		derivType = GImageDerivativeOps.getDerivativeType(imageType);
		integralType = GIntegralImageOps.getIntegralType(imageType);

		image = GeneralizedImageOps.createSingleBand(imageType, width, height);

		GImageMiscOps.fillUniform(image, rand, 0, 100);

		pts = new Point2D_I32[ NUM_POINTS ];
		scales = new double[ NUM_POINTS ];
		yaws = new double[ NUM_POINTS ];
		int border = 20;
		for( int i = 0; i < NUM_POINTS; i++ ) {
			int x = rand.nextInt(width-border*2)+border;
			int y = rand.nextInt(height-border*2)+border;
			pts[i] = new Point2D_I32(x,y);
			scales[i] = rand.nextDouble()*3+1;
			yaws[i] = 2.0*(rand.nextDouble()-0.5)*Math.PI;
		}

	}

	public class Brief512 extends PerformerBase {

		DescribePointBrief<I> alg = FactoryDescribePointAlgs.brief(FactoryBriefDefinition.gaussian2(new Random(123), 16, 512),
				FactoryBlurFilter.gaussian(imageType, 0, 4));

		@Override
		public void process() {
			alg.setImage(image);
			TupleDesc_B f = alg.createFeature();
			for( int i = 0; i < pts.length; i++ ) {
				Point2D_I32 p = pts[i];
				alg.process(p.x,p.y,f);
			}
		}
	}

	public class BriefSO512 extends PerformerBase {

		DescribePointBriefSO<I> alg = FactoryDescribePointAlgs.briefso(FactoryBriefDefinition.gaussian2(new Random(123), 16, 512),
				FactoryBlurFilter.gaussian(imageType, 0, 4));

		@Override
		public void process() {
			alg.setImage(image);
			TupleDesc_B f = alg.createFeature();
			for( int i = 0; i < pts.length; i++ ) {
				Point2D_I32 p = pts[i];
				alg.process(p.x,p.y,(float)yaws[i],(float)scales[i],f);
			}
		}
	}

	public class Describe<D extends TupleDesc> implements Performer {

		DescribeRegionPoint<I,D> alg;
		String name;

		public Describe(String name, DescribeRegionPoint<I,D> alg) {
			this.alg = alg;
			this.name = name;
		}

		@Override
		public void process() {
			alg.setImage(image);
			D d = null;
			for( int i = 0; i < pts.length; i++ ) {
				Point2D_I32 p = pts[i];
				if( alg.isInBounds(p.x,p.y,yaws[i],scales[i]))
					d=alg.process(p.x,p.y,yaws[i],scales[i],d);
			}
		}

		@Override
		public String getName() {
			return name;
		}
	}

	public void perform() {
		System.out.println("=========  Profile Image Size " + width + " x " + height + " ========== "+imageType.getSimpleName());
		System.out.println();

		ProfileOperation.printOpsPerSec(new Describe("SURF", FactoryDescribeRegionPoint.<I,II>surf(false, imageType)),TEST_TIME);
		ProfileOperation.printOpsPerSec(new Describe("MSURF", FactoryDescribeRegionPoint.<I,II>surf(true, imageType)),TEST_TIME);
		if( imageType == ImageFloat32.class )
			ProfileOperation.printOpsPerSec(new Describe("SIFT", FactoryDescribeRegionPoint.sift(1.6,5,4,false)),TEST_TIME);
		ProfileOperation.printOpsPerSec(new Brief512(),TEST_TIME);
		ProfileOperation.printOpsPerSec(new BriefSO512(),TEST_TIME);
	}

	public static void main( String arg[ ] ) {
		BenchmarkDescribe<ImageFloat32,?,?> alg = new BenchmarkDescribe(ImageFloat32.class);
//		BenchmarkDescribe<ImageUInt8,?,?> alg = new BenchmarkDescribe(ImageUInt8.class);

		alg.perform();
	}
}
