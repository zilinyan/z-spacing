/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.thickness.trakem2;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.plugin.TPlugIn;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.TranslationModel1D;
import mpicbg.trakem2.align.Align;
import mpicbg.trakem2.align.Align.Param;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.real.FloatType;

import org.janelia.thickness.inference.InferFromMatrix;
import org.janelia.thickness.inference.Options;
import org.janelia.thickness.mediator.OpinionMediatorWeightedAverage;

/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class LayerZPosition implements TPlugIn
{
	protected LayerSet layerset = null;
	static protected int radius = 10;
	static protected int iterations = 100;
	static protected double regularize = 0.6;
	static protected int innerIterations = 10;
	static protected double innerRegularize = 0.1;
	static protected boolean reorder = true;
	static protected double scale = -1;
	static protected boolean showMatrix = true;
	static protected Param siftParam = Align.param.clone();

	private Layer currentLayer( final Object... params )
	{
		final Layer layer;
		if (params != null && params[ 0 ] != null)
		{
			final Object param = params[ 0 ];
			if ( Layer.class.isInstance( param.getClass() ) )
				layer = ( Layer)param;
			else if ( LayerSet.class.isInstance( param.getClass() ) )
				layer = ( ( LayerSet )param ).getLayer( 0 );
			else if ( Displayable.class.isInstance( param.getClass() ) )
				layer = ( ( Displayable )param ).getLayerSet().getLayer( 0 );
			else layer = null;
		}
		else
		{
			final Display front = Display.getFront();
			if ( front == null )
				layer = Project.getProjects().get(0).getRootLayerSet().getLayer( 0 );
			else
				layer = front.getLayer();
		}
		return layer;
	}

	private static Rectangle getRoi( final LayerSet layerset )
	{
		final Roi roi;
		final Display front = Display.getFront();
		if ( front == null )
			roi = null;
		else
			roi = front.getRoi();
		if ( roi == null )
			return layerset.getBoundingBox();
		else
			return roi.getBounds();
	}

	private double suggestScale( final List< Layer > layers )
	{
		if ( layers.size() < 2 )
			return 0.0;

		final Layer layer1 = layers.get( 0 );
		final Layer layer2 = layers.get( 1 );
		final Calibration calib = layer1.getParent().getCalibration();
		final double width = ( calib.pixelWidth + calib.pixelHeight ) * 0.5;
		final double depth = calib.pixelDepth;

		return ( layer2.getZ() - layer1.getZ() ) * width / depth;
	}

	@Override
	public boolean setup( final Object... params )
	{
		if (params != null && params[ 0 ] != null)
		{
			final Object param = params[ 0 ];
			if ( LayerSet.class.isInstance( param.getClass() ) )
				layerset = ( LayerSet )param;
			else if ( Displayable.class.isInstance( param.getClass() ) )
				layerset = ( ( Displayable )param ).getLayerSet();
			else
				return false;
		}
		else
		{
			final Display front = Display.getFront();
			if ( front == null )
				layerset = Project.getProjects().get(0).getRootLayerSet();
			else
				layerset = front.getLayerSet();
		}
		return true;
	}

	static private int[] getPixels(
			final Layer layer,
			final Rectangle fov,
			final double s )
	{
		final Collection< Displayable > filteredDisplayables = layer.getDisplayables( Patch.class, fov );
		final ArrayList< Patch > filteredPatches = new ArrayList< Patch >();
		for ( final Displayable d : filteredDisplayables )
			if ( d.isVisible() )
				filteredPatches.add( ( Patch )d );

		if ( filteredPatches.size() > 0 )
		{
			final Image imgi = layer.getProject().getLoader().getFlatAWTImage(
	                layer,
	                fov,
	                s,
	                0xffffffff,
	                ImagePlus.COLOR_RGB,
	                Patch.class,
	                filteredPatches,
	                true,
	                new Color( 0x00ffffff, true ) );
			return ( int[] )new ColorProcessor( imgi ).getPixels();
		}
		else
			return null;
	}

	static public void optimize(
			final List< Layer > layers,
			final FloatProcessor matrix,
			final int radius,
			final int iterations,
			final double regularize,
			final int innerIterations,
			final double innerRegularize,
			final boolean reorder ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final Options options = Options.generateDefaultOptions();
		options.comparisonRange = radius;
		options.nIterations = iterations;
		options.shiftProportion = regularize;
		options.multiplierEstimationIterations = innerIterations;
		options.multiplierGenerationRegularizerWeight = innerRegularize;
		options.withReorder = reorder;
		options.withRegularization = true;
		options.minimumSectionThickness = 0.0;

		/* estimate reasonable integer step size */
		final double zMin = layers.get( 0 ).getZ();
		final double zScale = 1.0 / ( layers.get( 1 ).getZ() - zMin );

		final double[] lut = new double[ layers.size() ];
		for ( int i = 0; i < lut.length; ++i )
			lut[ i ] = ( layers.get( i ).getZ() - zMin ) * zScale;

		IJ.log( Arrays.toString( lut ) );

		final InferFromMatrix< TranslationModel1D > inference =
				new InferFromMatrix< TranslationModel1D >(
						new TranslationModel1D(),
						new OpinionMediatorWeightedAverage() );

		final RandomAccessibleInterval< FloatType > raMatrix = ImagePlusImgs.from( new ImagePlus( "", matrix ) );

		final double[] lutCorrected = inference.estimateZCoordinates( raMatrix, lut, options );

		IJ.log( Arrays.toString( lutCorrected ) );

		for ( int i = 0; i < lutCorrected.length; ++i )
			layers.get( i ).setZ( lutCorrected[ i ] / zScale + zMin );
	}

	static public FloatProcessor calculateNCCSimilarity(
			final List< Layer > layers,
			final Rectangle fov,
			final int r,
			final double s ) throws InterruptedException, ExecutionException
	{
		final FloatProcessor ip = new FloatProcessor( layers.size(), layers.size() );
		final float[] ipPixels = ( float[] )ip.getPixels();
		for ( int i = 0; i < ipPixels.length; ++i )
			ipPixels[ i ] = Float.NaN;
		ip.setMinAndMax( -0.2, 1.0 );

		final ImagePlus impMatrix;
		if ( showMatrix )
		{
			impMatrix = new ImagePlus( "Similarity matrix", ip );
			impMatrix.show();
		}
		else
			impMatrix = null;


		for ( int i = 0; i < layers.size(); ++i )
		{
			final int fi = i;
			final Layer li = layers.get( i );
			final int[] argbi = getPixels( li, fov, s );
			if ( argbi == null )
				continue;

			ip.setf( fi, fi, 1.0f );

	        final ExecutorService exec = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
			final ArrayList< Future< FloatProcessor > > tasks = new ArrayList< Future< FloatProcessor > >();

			for ( int j = i + 1; j < layers.size() && j <= i + r; ++j )
			{
				final int fj = j;
				final Layer lj = layers.get( j );
				tasks.add( exec.submit( new Runnable()
				{
					@Override
					public void run()
					{
						final int[] argbj = getPixels( lj, fov, s );
						if ( argbj != null )
						{
							final Double d = new RealSumARGBNCC( argbi, argbj ).call();
							ip.setf( fi, fj, d.floatValue() );
							ip.setf( fj, fi, d.floatValue() );
							if ( impMatrix != null )
								impMatrix.updateAndDraw();
						}
					}
				},
				ip ) );
			}

			for ( final Future< FloatProcessor > fu : tasks )
			{
				try
				{
					fu.get();
				}
				catch ( final InterruptedException e )
				{
					exec.shutdownNow();
					throw e;
				}
				catch ( final ExecutionException e )
				{
					exec.shutdownNow();
					throw e;
				}
			}

			tasks.clear();
			exec.shutdown();

			if ( impMatrix != null )
				impMatrix.updateAndDraw();
		}

		return ip;
	}

	/**
	 * Run thickness estimation for a list of layers using NCC similarity.
	 *
	 * @param layers
	 * @param fov
	 * @param r
	 * @param s
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	static public void runNCC(
			final List< Layer > layers,
			final Rectangle fov,
			final int r,
			final double s,
			final int iterations,
			final double regularize,
			final int innerIterations,
			final double innerRegularize,
			final boolean reorder ) throws InterruptedException, ExecutionException
	{
		final FloatProcessor matrix = calculateNCCSimilarity( layers, fov, r, s );

		try
		{
			optimize( layers, matrix, r, iterations, regularize, innerIterations, innerRegularize, reorder );
		}
		catch ( final Exception e )
		{
			throw new ExecutionException( e.getCause() );
		}
	}

	/**
	 * Run plugin with NCC similarity.
	 *
	 * @param layers
	 * @param fov
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	public void invokeNCC( final List< Layer > layers, final Rectangle fov ) throws InterruptedException, ExecutionException
	{
		final GenericDialog gd = new GenericDialog( "Correct layer z-positions - NCC" );
		gd.addNumericField( "scale :", scale < 0 ? suggestScale( layers ) : scale, 2, 6, "" );
		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		scale = gd.getNextNumber();

		runNCC( layers, fov, radius, scale, iterations, regularize, innerIterations, innerRegularize, reorder );
	}

	static public FloatProcessor calculateSIFTSimilarity(
			final List< Layer > layers,
			final Rectangle fov,
			final int r,
			final Param p ) throws InterruptedException, ExecutionException
	{
		return null;
	}

	static public void runSIFT(
			final List< Layer > layers,
			final Rectangle fov,
			final int r,
			final Param p ) throws InterruptedException, ExecutionException
	{
		final FloatProcessor ip = calculateSIFTSimilarity( layers, fov, r, p );



		new ImagePlus( "matrix", ip ).show();
	}

	/**
	 * Run plugin with SIFT consensus similarity.
	 *
	 * @param layers
	 * @param fov
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	public void invokeSIFT( final List< Layer > layers, final Rectangle fov ) throws InterruptedException, ExecutionException
	{
		final GenericDialog gd = new GenericDialog( "Correct layer z-positions - SIFT consensus" );
		gd.addMessage( "Scale Invariant Features :" );
		siftParam.addSIFTFields( gd );
		gd.addMessage( "Consensus Filter :" );
		siftParam.addGeometricConsensusFilterFields( gd );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		siftParam.readSIFTFields( gd );
		siftParam.readGeometricConsensusFilterFields( gd );


		runSIFT( layers, fov, radius, siftParam.clone() );
	}




	@Override
	public Object invoke( final Object... params )
	{
		if ( !setup( params ) )
			return null;

		final Layer layer = currentLayer( params );
		final GenericDialog gd = new GenericDialog( "Correct layer z-positions" );
		Utils.addLayerRangeChoices( layer, gd );
		gd.addMessage( "Layer neighbor range :" );
		gd.addNumericField( "test_maximally :", radius, 0, 6, "layers" );
		gd.addMessage( "Optimizer :" );
		gd.addNumericField( "outer_iterations :", iterations, 0, 6, "" );
		gd.addNumericField( "outer_regularization :", regularize, 2, 6, "" );
		gd.addNumericField( "inner_iterations :", innerIterations, 0, 6, "" );
		gd.addNumericField( "inner_regularization :", innerRegularize, 2, 6, "" );
		gd.addCheckbox( " allow_reordering", reorder );


		gd.addChoice(
				"Similarity_method :",
				new String[]{ "NCC (aligned)", "SIFT consensus (unaligned)" }, "NCC (aligned)" );
		gd.addCheckbox( "show_matrix", showMatrix );
		gd.showDialog();
		if ( gd.wasCanceled() )
			return null;

		final List< Layer > layers =
				layerset.getLayers().subList(
						gd.getNextChoiceIndex(),
						gd.getNextChoiceIndex() + 1 );
		radius = ( int )gd.getNextNumber();
		final int method = gd.getNextChoiceIndex();
		showMatrix = gd.getNextBoolean();
		try
		{
			switch ( method )
			{
			case 1:
				invokeSIFT( layers, getRoi( layerset ) );
				break;
			default :
				invokeNCC( layers, getRoi( layerset ) );
			}
		}
		catch ( final InterruptedException e )
		{
			Utils.log( "Layer Z-Spacing Correction interrupted." );
		}
		catch ( final ExecutionException e )
		{
			Utils.log( "Layer Z-Spacing Correction ExecutiuonException occurred:" );
			e.printStackTrace( System.out );
		}

		return null;
	}

	@Override
	public boolean applies( final Object ob )
	{
		return true;
	}
}