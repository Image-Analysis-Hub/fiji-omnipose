package fiji.plugin.appose.omnipose;

import static fiji.plugin.appose.ApposeUtils.addROIs;
import static fiji.plugin.appose.ApposeUtils.clearOutsideRoi;
import static fiji.plugin.appose.ApposeUtils.getAxisInfo;
import static fiji.plugin.appose.ApposeUtils.rawWraps;
import static fiji.plugin.appose.ApposeUtils.transferCalibration;

import java.awt.Color;
import java.awt.Rectangle;
import java.io.IOException;

import org.apposed.appose.BuildException;
import org.apposed.appose.TaskException;
import org.scijava.command.Previewable;
import org.scijava.ui.config.fiji.ConfigFijiPluginFrame;
import org.scijava.ui.config.visitors.gui.FrameBuilder.ConfigFrame;
import org.scijava.ui.config.visitors.gui.FrameBuilder.ConfigFrame.Progress;

import fiji.plugin.appose.listeners.FijiApposeProgressListener;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.Duplicator;
import ij.plugin.frame.RoiManager;
import net.imagej.ImgPlus;
import net.imglib2.appose.util.ApposeTaskListener;
import net.imglib2.appose.util.AxisInfo;
import net.imglib2.img.Img;
import net.imglib2.omnipose.OmniposeOutput;
import net.imglib2.omnipose.OmniposeParameters;
import net.imglib2.omnipose.OmniposeRunner2;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

public class OmniposePluginFrame extends ConfigFijiPluginFrame< OmniposeConfig > implements Previewable
{

	private OmniposeRunner2 runner;

	protected OmniposeParameters toParams( final OmniposeConfig config )
	{
		final String selection = config.builtinOrCustom().getSelection().getKey();
		final boolean isBuiltin = selection.equals( "BUILTIN_MODEL" );

		final OmniposeParameters params = OmniposeParameters.builder()
				.model( isBuiltin ? config.builtinModel().getValue() : null )
				.customModel( isBuiltin ? null : config.customModel().getValue() )
				.diameter( config.diameter().getValue() )
				.channels( config.channel().getValue(), 0 )
				.minSize( config.minSize().getValue() )
				.normalize( config.normalize().getValue() )
				.resample( true ) // Must be true here, as we expect the output
									// to have the same size as the input.
				.maskThreshold( config.maskThreshold().getValue() )
				.flowThreshold( config.flowThreshold().getValue() )
				.tileOverlap( config.tileOverlap().getValue() )
				.computeFlows( config.exportFlows().getValue() )
				.do3D( config.do3D().getValue() )
				.stitchThreshold( config.stitchThreshold().getValue() )
				.nIter( config.nIter().getValue() )
				.torchVersion( config.torchVersion().getValue() )
				.useGpu( config.useGpu().getValue() )
				.build();
		return params;
	}

	@Override
	protected OmniposeConfig createConfig( final ImagePlus imp )
	{
		final int nChannels = imp.getNChannels();
		final double pixelSize = imp.getCalibration().pixelWidth;
		final String units = imp.getCalibration().getUnit();
		return new OmniposeConfig( nChannels, pixelSize, units );
	}

	@SuppressWarnings( "unchecked" )
	protected ImagePlus[] exec( final ImagePlus imp, final OmniposeParameters params, final ApposeTaskListener listener ) throws BuildException, IOException, InterruptedException, TaskException
	{
		if ( runner == null )
		{
			runner = OmniposeRunner2.create( listener, params.torchVersion );
			runner.init();
		}

		// Wrap ImagePlus.
		Roi initialRoi = imp.getRoi();
		if ( initialRoi != null )
			initialRoi = ( Roi ) initialRoi.clone();
		@SuppressWarnings( "rawtypes" )
		final ImgPlus input = rawWraps( imp );
		final AxisInfo inputAxes = getAxisInfo( input );

		// Exec.
		runner.setInput( input, inputAxes );
		runner.run( params );

		// Process outputs.
		final OmniposeOutput< UnsignedShortType > outputs;
		final Img< UnsignedShortType > labels = runner.getOutputLabels();
		clearOutsideRoi( labels, initialRoi );
		if ( params.computeFlows )
		{
			final Img< UnsignedByteType > flows = runner.getOutputFlows();
			clearOutsideRoi( flows, initialRoi );
			outputs = new OmniposeOutput<>(
					labels,
					inputAxes.removeChannelDim(),
					flows,
					( inputAxes.C() < 0 ) ? inputAxes.insertChannelDim( 2 ) : inputAxes );
		}
		else
		{
			outputs = new OmniposeOutput<>( labels, inputAxes.removeChannelDim() );
		}

		final ImagePlus[] imps = Omnipose.toImp( outputs );
		for ( final ImagePlus out : imps )
			transferCalibration( imp, out, initialRoi );
		imps[ 0 ].setTitle( imp.getTitle() + "_Omnipose" );
		if ( params.computeFlows )
			imps[ 1 ].setTitle( imp.getTitle() + "_flows_Omnipose" );
		return imps;
	}

	@Override
	protected ConfigFrame showUI()
	{
		final ConfigFrame frame = super.showUI();
		// Close the runner when the frame is closed.
		frame.addWindowListener( new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowClosed( final java.awt.event.WindowEvent e )
			{
				if ( runner != null )
					runner.close();
			}
		} );
		return frame;
	}

	@Override
	public void run( final Progress progress ) throws Exception
	{
		process( progress );
		super.run( progress );
	}

	/**
	 * Process the image that was active then plugin was launched, with the
	 * current configuration.
	 * 
	 * @param progress
	 *            the progress to report to
	 * @throws IOException
	 * @throws BuildException
	 */
	private void process( final Progress progress ) throws IOException, BuildException
	{
		final ImagePlus imp = getImagePlus();
		if ( imp == null )
		{
			progress.message( "No image selected, aborting." );
			return;
		}
		progress.message( "Starting process..." );
		final Roi roi = imp.getRoi();
		process( imp, progress, 0 );
		imp.setRoi( roi );
	}

	/**
	 * Processes the specified image with the current configuration. The tOrigin
	 * is used to translate the ROIs in time in the case of a preview.
	 * 
	 * @param imp
	 *            the image to process
	 * @param progress
	 *            the progress to report to
	 * @param tOrigin
	 *            the time origin to translate the ROIs in time
	 * @throws IOException
	 * @throws BuildException
	 */
	protected void process( final ImagePlus imp, final Progress progress, final int tOrigin ) throws IOException, BuildException
	{
		final OmniposeConfig config = getConfig();
		try
		{
			// Convert config to Cellpose parameters.
			final OmniposeParameters params = toParams( getConfig() );

			// Exec.
			final FijiApposeProgressListener listener = new FijiApposeProgressListener( progress, config.getName() );
			final ImagePlus[] outputs = exec( imp, params, listener );

			// Unwrap the outputs and show them.
			final ImagePlus labels = outputs[ 0 ];
			if ( config.exportROIs().getValue() && imp.getNSlices() == 1 )
			{
				final boolean multipleChannels = imp.getNChannels() > 1;
				addROIs( labels, config.getName(), Color.YELLOW, tOrigin, multipleChannels );
				RoiManager.getInstance2().runCommand( "Show All" );
			}
			if ( config.exportLabels().getValue() )
				labels.show();
			if ( config.exportFlows().getValue() && outputs.length > 1 )
			{
				final ImagePlus flows = outputs[ 1 ];
				flows.show();
			}
		}
		catch ( final Exception e )
		{
			IJ.handleException( e );
		}
	}

	// Previewable

	@Override
	public void preview()
	{
		final ImagePlus imp = getImagePlus();
		if ( imp == null )
		{
			IJ.showStatus( "No image selected, aborting." );
			return;
		}

		final Roi roi = imp.getRoi();
		final Duplicator dup = new Duplicator();
		final int z = imp.getSlice();
		final int t = imp.getFrame();
		final ImagePlus crop = dup.run( imp, 1, imp.getNChannels(), z, z, t, t );
		// Translate origin so that the ROIs are correctly positioned.
		if ( roi != null )
		{
			crop.getCalibration().xOrigin = roi.getBounds().x;
			crop.getCalibration().yOrigin = roi.getBounds().y;
			final Rectangle bounds = roi.getBounds();
			final Roi clone = ( Roi ) roi.clone();
			clone.translate( -bounds.x, -bounds.y );
			crop.setRoi( clone );
			// We need the ROI so that the outside of it are properly masked.
		}

		final Progress progress = new IJProgress();
		progress.message( "Starting process..." );
		final int tOrigin = t - 1;
		try
		{
			process( crop, progress, tOrigin );
		}
		catch ( IOException | BuildException e )
		{
			IJ.handleException( e );
			e.printStackTrace();
		}
		finally
		{
			crop.changes = false;
			crop.close();
			imp.setRoi( roi );
		}
	}

	@Override
	public void cancel()
	{
		// We don't cancel preview.
	}
}
