/**
 * 
 */
package org.janelia.thickness.lut;

import net.imglib2.realtransform.InvertibleRealTransform;

/**
 * Abstract base class for LUT based transforms.
 * 
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
abstract public class AbstractLUTRealTransform implements InvertibleRealTransform
{
	final protected int numSourceDimensions;
	final protected int numTargetDimensions;
	final protected int lutMaxIndex;
	final protected double[] lut;
	
	public AbstractLUTRealTransform( final double[] lut, final int numSourceDimensions, final int numTargetDimensions )
	{
		this.lut = lut;
		this.numSourceDimensions = numSourceDimensions;
		this.numTargetDimensions = numTargetDimensions;

		lutMaxIndex = lut.length - 1;
	}
	
	protected double apply( final double x )
	{
		final int xFloor = ( int )x;
		final double dx = x - xFloor;
		return ( lut[ xFloor + 1 ] - lut[ xFloor ] ) * dx + lut[ xFloor ];
	}

	protected double applyChecked( final double x )
	{
		if ( x < 0 ) return -Double.MAX_VALUE;
		else if ( x > lutMaxIndex ) return Double.MAX_VALUE;
		else if ( x == lutMaxIndex ) return lut[lutMaxIndex];
		else return apply( x );
	}
	
	/**
	 * Finds the LUT index i of the largest value smaller than or equal y for
	 * all y in [lut[0],lut[max]] both inclusive.  Only exception is lut[max]
	 * for which it returns max-1.  This is the correct behavior for
	 * interpolating between lut[i] and lut[i + i] including lut[max].
	 * 
	 * Implemented as bin-search.
	 * 
	 * @param y
	 * @return
	 */
	protected int findFloorIndex( final double y )
	{
		int min = 0;
		int max = lutMaxIndex;
		int i = max >> 1;
		do
		{
			if ( lut[ i ] > y )
				max = i;
			else
				min = i;
			i = ( ( max - min ) >> 1 ) + min;
		}
		while ( i != min );
		return i;

	}
	
	protected double applyInverse( final double y )
	{
		final int i = findFloorIndex( y );
		
		final double x1 = lut[ i ];
		final double x2 = lut[ i + 1 ];
		
		return ( y - x1 )  / ( x2 - x1 ) + i;
	}
	
	protected double applyInverseChecked( final double y )
	{
		if ( y < lut[ 0 ] )
			return -Double.MAX_VALUE;
		else if ( y > lut[ lutMaxIndex ] )
			return Double.MAX_VALUE;
		else
			return applyInverse( y );
	}
	
	public double minTransformedCoordinate()
	{
		return lut[ 0 ];
	}

	public double maxTransformedCoordinate()
	{
		return lut[ lutMaxIndex ];
	}

	@Override
	public int numSourceDimensions()
	{
		return numSourceDimensions;
	}

	@Override
	public int numTargetDimensions()
	{
		return numTargetDimensions;
	}
}
