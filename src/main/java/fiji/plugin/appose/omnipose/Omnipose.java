package fiji.plugin.appose.omnipose;

import static fiji.plugin.appose.ApposeUtils.clearOutsideRoi;
import static fiji.plugin.appose.ApposeUtils.getAxisInfo;
import static fiji.plugin.appose.ApposeUtils.rawWraps;
import static fiji.plugin.appose.ApposeUtils.transferCalibration;
import static fiji.plugin.appose.ApposeUtils.useGlasbeyDarkLUT;

import java.io.IOException;

import org.apposed.appose.BuildException;
import org.apposed.appose.TaskException;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.StackStatistics;
import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.appose.util.ApposeTaskListener;
import net.imglib2.appose.util.AxisInfo;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.omnipose.OmniposeOutput;
import net.imglib2.omnipose.OmniposeParameters;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.UnsignedByteType;

public class Omnipose
{

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public static ImagePlus[] omnipose(
			final ImagePlus imp,
			final OmniposeParameters params,
			final ApposeTaskListener listener ) throws BuildException, IOException, InterruptedException, TaskException
	{
		Roi initialRoi = imp.getRoi();
		if ( initialRoi != null )
			initialRoi = ( Roi ) initialRoi.clone();
		final ImgPlus input = rawWraps( imp );
		final AxisInfo inputAxes = getAxisInfo( input );
		final OmniposeOutput outputs = net.imglib2.omnipose.Omnipose.omnipose( input, inputAxes, params, listener );
		clearOutsideRoi( outputs.labels, initialRoi );
		if ( params.computeFlows )
			clearOutsideRoi( outputs.flows, initialRoi );

		final ImagePlus[] imps = toImp( outputs );
		for ( final ImagePlus out : imps )
			transferCalibration( imp, out, initialRoi );
		imps[ 0 ].setTitle( imp.getTitle() + "_Cellpose-3" );
		if ( params.computeFlows )
			imps[ 1 ].setTitle( imp.getTitle() + "_flows_Cellpose-3" );
		return imps;
	}

	public static < R extends IntegerType< R > & NativeType< R > > ImagePlus[] toImp( final OmniposeOutput< R > outputs )
	{
		final RandomAccessibleInterval< R > labels = outputs.labels;
		final ImagePlus labelsImp = ImageJFunctions.wrap( labels, "labels" );

		// Set dimensionality. We assume output are always XYCZT.
		final AxisInfo axesLabels = outputs.axesLabels;
		final int nC = ( int ) axesLabels.nChannels( labels );
		final int nZ = ( int ) axesLabels.nZ( labels );
		final int nT = ( int ) axesLabels.nTimePoints( labels );
		labelsImp.setDimensions( nC, nZ, nT );
		labelsImp.getCalibration().xOrigin = labels.min( 0 );
		labelsImp.getCalibration().yOrigin = labels.min( 1 );

		// Set display range and LUT.
		final StackStatistics stats = new StackStatistics( labelsImp );
		labelsImp.setDisplayRange( stats.min, stats.max );
		useGlasbeyDarkLUT( labelsImp );

		// Deal with the flows.
		if ( outputs.flows != null )
		{
			final RandomAccessibleInterval< UnsignedByteType > flows = outputs.flows;
			ImagePlus flowsImp = ImageJFunctions.wrap( flows, "flows" );

			final AxisInfo axesFlows = outputs.axesFlows;
			final int nCFlows = ( int ) axesFlows.nChannels( flows );
			final int nZFlows = ( int ) axesFlows.nZ( flows );
			final int nTFlows = ( int ) axesFlows.nTimePoints( flows );
			flowsImp.setDimensions( nCFlows, nZFlows, nTFlows );
			flowsImp.getCalibration().xOrigin = labels.min( 0 );
			flowsImp.getCalibration().yOrigin = labels.min( 1 );
			flowsImp.getProcessor().resetMinAndMax();
			flowsImp = new CompositeImage( flowsImp );
			flowsImp.setDisplayMode( CompositeImage.COMPOSITE );
			return new ImagePlus[] { labelsImp, flowsImp };
		}
		return new ImagePlus[] { labelsImp };
	}

	private Omnipose()
	{}
}
