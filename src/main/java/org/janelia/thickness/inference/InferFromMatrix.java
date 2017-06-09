package org.janelia.thickness.inference;

import java.util.Arrays;

import org.janelia.thickness.EstimateScalingFactors;
import org.janelia.thickness.ShiftCoordinates;
import org.janelia.thickness.inference.fits.AbstractCorrelationFit;
import org.janelia.thickness.inference.visitor.LazyVisitor;
import org.janelia.thickness.inference.visitor.Visitor;
import org.janelia.thickness.lut.LUTRealTransform;
import org.janelia.thickness.lut.PermutationTransform;
import org.janelia.utility.MatrixStripConversion;
import org.janelia.utility.arrays.ArraySortedIndices;
import org.janelia.utility.arrays.ReplaceNaNs;

import mpicbg.models.AffineModel1D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.ConstantUtils;
import net.imglib2.view.IntervalView;
import net.imglib2.view.TransformView;
import net.imglib2.view.Views;

/**
 *
 * @author Philipp Hanslovsky &lt;hanslovskyp@janelia.hhmi.org&gt;
 *
 */
public class InferFromMatrix
{

	private final AbstractCorrelationFit correlationFit;

	public enum RegularizationType
	{
		NONE,
		IDENTITY,
		BORDER
	}

	public static interface Regularizer
	{
		public void regularize( double[] coordinates, Options options ) throws Exception;
	}

	public static class NoRegularization implements Regularizer
	{

		@Override
		public void regularize( final double[] coordinates, final Options options )
		{
			// do not do anything
		}
	}

	public static abstract class ModelRegularization implements Regularizer
	{
		private final Model< ? > m;

		private final double[] regularizationValues;

		private final double[] weights;

		private final double[] dummy;

		protected ModelRegularization( final Model< ? > m, final double[] regularizationValues, final double[] weights )
		{
			this.m = m;
			this.regularizationValues = regularizationValues;
			this.weights = weights;
			this.dummy = new double[ 1 ];
		}

		protected abstract double[] extractRelevantCoordinates( double[] coordinates );

		@Override
		public void regularize( final double[] coordinates, final Options options ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
		{
			final double[] relevantCoordinates = extractRelevantCoordinates( coordinates );
			m.fit( new double[][] { relevantCoordinates }, new double[][] { regularizationValues }, weights );

			for ( int i = 0; i < coordinates.length; ++i )
			{
				dummy[ 0 ] = coordinates[ i ];
				m.applyInPlace( dummy );
				coordinates[ i ] = dummy[ 0 ];
			}
		}
	}

	public static class BorderRegularization extends ModelRegularization
	{
		private final double[] relevantCoordinates;

		public BorderRegularization( final Model< ? > m, final int length )
		{
			super( m, new double[] { 0, length - 1 }, new double[] { 1.0, 1.0 } );
			this.relevantCoordinates = new double[ 2 ];
		}

		@Override
		protected double[] extractRelevantCoordinates( final double[] coordinates )
		{
			relevantCoordinates[ 0 ] = coordinates[ 0 ];
			relevantCoordinates[ 1 ] = coordinates[ coordinates.length - 1 ];
			return relevantCoordinates;
		}
	}

	public static class IdentityRegularization extends ModelRegularization
	{
		public IdentityRegularization( final Model< ? > m, final int length )
		{
			super( m, range( 0, length, 1 ), constVals( length, 1.0 ) );
		}

		@Override
		protected double[] extractRelevantCoordinates( final double[] coordinates )
		{
			return coordinates;
		}

		public static double[] range( int start, final int stop, final int step )
		{
			final double[] result = new double[ ( stop - start ) / step ];
			for ( int i = 0; i < result.length; ++i, start += step )
				result[ i ] = start;
			return result;
		}

		public static double[] constVals( final int length, final double val )
		{
			final double[] result = new double[ length ];
			for ( int i = 0; i < result.length; ++i )
				result[ i ] = val;
			return result;
		}
	}

	public InferFromMatrix( final AbstractCorrelationFit correlationFit )
	{
		super();

		this.correlationFit = correlationFit;
	}

	public < T extends RealType< T > & NativeType< T > > double[] estimateZCoordinates(
			final RandomAccessibleInterval< T > matrix,
			final double[] startingCoordinates,
			final Options options ) throws Exception
	{
		return estimateZCoordinates(
				matrix,
				startingCoordinates,
				new LazyVisitor(),
				options );
	}

	public < T extends RealType< T > & NativeType< T > > double[] estimateZCoordinates(
			final RandomAccessibleInterval< T > inputMatrix,
			final double[] startingCoordinates,
			final Visitor visitor,
			final Options options ) throws Exception
	{
		return estimateZCoordinates(
				inputMatrix,
				startingCoordinates,
				new double[ 0 ],
				Arrays.stream( new double[ startingCoordinates.length ] ).map( d -> 1.0 ).toArray(),
				ConstantUtils.constantRandomAccessibleInterval( new DoubleType( 1.0 ), inputMatrix.numDimensions(), inputMatrix ),
				Arrays.stream( new double[ startingCoordinates.length ] ).map( d -> 1.0 ).toArray(),
				visitor,
				options );
	}

	public < T extends RealType< T > & NativeType< T >, W extends RealType< W > > double[] estimateZCoordinates(
			final RandomAccessibleInterval< T > inputMatrix,
			final double[] startingCoordinates,
			final double[] functionEstimate,
			final double[] scalingFactors,
			final RandomAccessibleInterval< W > estimateWeights,
			final double[] shiftWeights,
			final Visitor visitor,
			final Options options ) throws Exception
	{

		final double[] lut = startingCoordinates.clone();
		final int n = ( int ) inputMatrix.dimension( 0 );
		final int[] permutationLut = new int[ n ];
		final int[] inverse = permutationLut.clone();
		final int nMatrixDim = inputMatrix.numDimensions();
		final RandomAccessibleInterval< double[] >[] correlationFitsStore = new RandomAccessibleInterval[] { null };

		double[] permutedLut = lut.clone(); // sorted lut
		final double[] scalingFactorsPrevious = scalingFactors.clone();
		ArraySortedIndices.sort( permutedLut, permutationLut, inverse );

		final ArrayImg< T, ? > inputScaledStrip = new ArrayImgFactory< T >().create( new long[] { 2 * options.comparisonRange + 1, n }, inputMatrix.randomAccess().get() );

		final RandomAccessibleInterval< T > inputScaledMatrix = MatrixStripConversion.stripToMatrix( inputScaledStrip, inputMatrix.randomAccess().get() );
		for ( Cursor< T > source = Views.flatIterable( inputMatrix ).cursor(), target = Views.flatIterable( inputScaledMatrix ).cursor(); source.hasNext(); )
			target.next().set( source.next() );

		final Regularizer regularizer;
		switch ( options.regularizationType )
		{
		case BORDER:
		{
			regularizer = new BorderRegularization( new AffineModel1D(), n );
			break;
		}
		case IDENTITY:
		{
			regularizer = new IdentityRegularization( new AffineModel1D(), n );
			break;
		}
		case NONE:
		{
			regularizer = new NoRegularization();
			break;
		}
		default:
		{
			regularizer = new NoRegularization();
			break;
		}
		}

		final double[] shiftsArray = new double[ n ];
		final double[] weightSums = new double[ n ];

		for ( int iteration = 0; iteration < options.nIterations; ++iteration )
		{

			final long t0 = System.nanoTime();
			// scaling factors always in permuted order

			final PermutationTransform permutation = new PermutationTransform( inverse, nMatrixDim, nMatrixDim ); // need
			// to
			// create
			// Transform
			// into
			// source?
			final IntervalView< T > matrix = Views.interval( new TransformView< >( inputMatrix, permutation ), inputMatrix );
			final IntervalView< T > scaledMatrix = Views.interval( new TransformView< >( inputScaledMatrix, permutation ), inputScaledMatrix );

			if ( iteration == 0 )
				visitor.act( iteration, matrix, scaledMatrix, lut, permutationLut, inverse, scalingFactors, correlationFitsStore[ 0 ], 0.0 );

			Arrays.fill( shiftsArray, 0.0 );
			Arrays.fill( weightSums, 0.0 );;

			final double[] shifts = this.getMediatedShifts(
					matrix,
					scaledMatrix,
					permutedLut,
					scalingFactors,
					iteration,
					correlationFitsStore,
					shiftsArray,
					weightSums,
					estimateWeights,
					shiftWeights,
					options );

			final double avgShift = this.applyShifts(
					permutedLut, // rewrite interface to use view on permuted
					// lut? probably not
					shifts,
					weightSums,
					startingCoordinates,
					permutation.copyToDimension( 1, 1 ),
					options );

			ReplaceNaNs.replace( permutedLut );

			if ( !options.withReorder )
				preventReorder( permutedLut, options ); //

			//    		if ( options.withRegularization )
			regularizer.regularize( permutedLut, options );

			updateArray( permutedLut, lut, inverse );
			updateArray( scalingFactors, scalingFactorsPrevious, inverse );
			permutedLut = lut.clone();
			ArraySortedIndices.sort( permutedLut, permutationLut, inverse );
			updateArray( scalingFactorsPrevious, scalingFactors, permutationLut );
			final long t1 = System.nanoTime();
			//			System.out.println( "time: " + ( t1 - t0 ) );

			visitor.act( iteration + 1, matrix, scaledMatrix, lut, permutationLut, inverse, scalingFactors, correlationFitsStore[ 0 ], avgShift );

		}

		return lut;
	}

	public < T extends RealType< T >, W extends RealType< W > > double[] getMediatedShifts(
			final RandomAccessibleInterval< T > matrix,
			final RandomAccessibleInterval< T > scaledMatrix,
			final double[] lut,
			final double[] scalingFactors,
			final int iteration,
			final RandomAccessibleInterval< double[] >[] correlationFitsStore,
			final double[] shiftsArray,
			final double[] weightSums,
			final RandomAccessibleInterval< W > estimateWeightMatrix,
			final double[] shiftWeights,
			final Options options ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{

		final int nMatrixDimensions = scaledMatrix.numDimensions();
		final LUTRealTransform transform = new LUTRealTransform( lut, nMatrixDimensions, nMatrixDimensions );

		// use scaled matrix
		// TODO about 1/4 of runtime happens here
		final RandomAccessibleInterval< double[] > fits =
				correlationFit.estimateFromMatrix( scaledMatrix, lut, transform, estimateWeightMatrix, options );
		correlationFitsStore[ 0 ] = fits;

		// use original matrix to estimate scaling factors
		// TODO more than half of runtime happens here -- only option to keep number of iterations low?
		EstimateScalingFactors.estimateQuadraticFromMatrix( matrix,
				scalingFactors,
				lut,
				fits,
				options.scalingFactorRegularizerWeight,
				options.comparisonRange,
				options.scalingFactorEstimationIterations,
				estimateWeightMatrix );

		// write scaled matrix to scaledMatrix
		final RandomAccess< T > matrixRA = matrix.randomAccess();
		final RandomAccess< T > scaledMatrixRA = scaledMatrix.randomAccess();
		for ( int z = 0; z < lut.length; ++z )
		{
			matrixRA.setPosition( z, 0 );
			scaledMatrixRA.setPosition( z, 0 );
			final int max = Math.min( lut.length, z + options.comparisonRange + 1 );
			for ( int k = Math.max( 0, z - options.comparisonRange ); k < max; ++k )
			{
				matrixRA.setPosition( k, 1 );
				scaledMatrixRA.setPosition( k, 1 );
				scaledMatrixRA.get().set( matrixRA.get() );
				if ( k != z )
					scaledMatrixRA.get().mul( scalingFactors[ z ] * scalingFactors[ k ] );
			}
		}

		// use scaled matrix to collect shifts
		ShiftCoordinates.collectShiftsFromMatrix(
				lut,
				scaledMatrix,
				scalingFactors,
				fits,
				shiftsArray,
				weightSums,
				shiftWeights,
				options );

		final double[] mediatedShifts = new double[ lut.length ];
		mediateShifts( shiftsArray, weightSums, mediatedShifts );

		return mediatedShifts;
	}

	public double applyShifts(
			final double[] coordinates,
			final double[] shifts,
			final double[] weightSums,
			final double[] regularizerCoordinates,
			final PermutationTransform permutation,
			final Options options )
	{
		double averageShift = 0.0;
		int averageShiftContributors = 0;

		for ( int i = 0; i < shifts.length; ++i )
		{
			final double shift = shifts[ i ];
			if ( Double.isNaN( shift ) ) {
				interpolateShift( shifts, i );
				continue;
			}
			averageShift += shift;
			averageShiftContributors += 1;
		}

		averageShift /= averageShiftContributors;
		if ( averageShiftContributors < 2 )
			return averageShift;
		else
			for ( int i = 0; i < coordinates.length; ++i )
			{
				// TODO normalize by local average only instead of average?
				final double otherShift = averageShift;
				final double shift = shifts[ i ];
				final double w = 1.0;
				final double actualShift = ( w * shift + options.pairwisePotentialRegularizer * otherShift ) / ( w + options.pairwisePotentialRegularizer + options.shiftProportion );
				coordinates[ i ] += actualShift;
			}
		return averageShift;
	}

	public void preventReorder(
			final double[] coordinates,
			final Options options )
	{
		for ( int i = 1; i < coordinates.length; i++ )
		{
			final double previous = coordinates[ i - 1 ];
			if ( previous > coordinates[ i ] )
				coordinates[ i ] = previous + options.minimumSectionThickness;
		}
	}

	public void updateArray( final double[] source, final double[] target, final int[] permutation )
	{
		for ( int i = 0; i < target.length; i++ )
			target[ permutation[ i ] ] = source[ i ];
	}

	public static void mediateShifts(
			final double[] shifts,
			final double[] weightSums,
			final double[] mediatedShifts )
	{
		for ( int i = 0; i < mediatedShifts.length; ++i )
			mediatedShifts[ i ] = shifts[ i ] / weightSums[ i ];
	}

	public static < T extends RealType< T > > void fillWeightStrip( final RandomAccessibleInterval< T > strip, final double[] weights, final T t )
	{

		final int n = weights.length;
		final int range = ( int ) ( strip.dimension( 0 ) / 2 );

		for ( final T s : Views.flatIterable( strip ) )
			s.setReal( Double.NaN );

		{
			final RandomAccess< T > access = MatrixStripConversion.stripToMatrix( strip, t ).randomAccess();
			for ( int z1 = 0; z1 < n; ++z1 )
			{
				access.setPosition( z1, 1 );
				final double w1 = weights[ z1 ];
				for ( int z2 = z1 - range; z2 <= z1 + range; ++z2 )
				{
					if ( z2 < 0 )
						continue;
					else if ( z2 >= n )
						break;
					access.setPosition( z2, 0 );
					access.get().setReal( w1 * weights[ z2 ] );
				}
			}
		}
	}

	public static double interpolateShift( final double[] shifts, final int i )
	{
		double lowerShift = 0.0, upperShift = 0.0;
		int lowerIndex = -1, upperIndex = -1;
		for ( int down = i - 1; down >= 0; --down )
		{
			final double s = shifts[ down ];
			if ( !Double.isNaN( s ) )
			{
				lowerIndex = down;
				lowerShift = s;
				break;
			}
		}
		for ( int up = i + 1; up < shifts.length; ++up )
		{
			final double s = shifts[ up ];
			if ( !Double.isNaN( s ) )
			{
				upperIndex = up;
				upperShift = s;
				break;
			}
		}

		final double s;
		if ( lowerIndex == -1 )
			s = upperShift;
		else if ( upperIndex == -1 )
			s = lowerShift;
		else
		{
			final double wLower = upperIndex - i;
			final double wUpper = i - lowerIndex;
			s = ( wLower * lowerShift + wUpper * upperShift ) / ( wLower + wUpper );
		}
		shifts[ i ] = s;
		return s;
	}
}
