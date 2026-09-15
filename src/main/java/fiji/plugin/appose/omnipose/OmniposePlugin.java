package fiji.plugin.appose.omnipose;

import static fiji.plugin.appose.ApposeUtils.addROIs;

import java.awt.Color;

import org.scijava.ui.config.fiji.ConfigFijiPluginPreviewable;

import fiji.plugin.appose.listeners.FijiApposeProgressListener;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.frame.RoiManager;
import net.imglib2.omnipose.OmniposeParameters;

public class OmniposePlugin extends ConfigFijiPluginPreviewable< OmniposeConfig >
{

	protected OmniposeParameters toParams( final OmniposeConfig config )
	{
		final String selection = config.builtinOrCustom().getSelection().getKey();
		final boolean isBuiltin = selection.equals( "BUILTIN_MODEL" );

		final OmniposeParameters params = OmniposeParameters.builder()
				.model( isBuiltin ? config.builtinModel().getValue() : null )
				.customModel( isBuiltin ? null :  config.customModel().getValue() )
				.diameter( config.diameter().getValue() )
				.channels( config.channel().getValue(), 0 )
				.minSize( config.minSize().getValue() )
				.normalize( config.normalize().getValue() )
				.resample( true ) // Must be true here, as we expect the output to have the same size as the input.
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

	@Override
	protected void process( final ImagePlus imp, final int tOrigin )
	{
		try
		{
			// Convert config to Cellpose parameters.
			final OmniposeParameters params = toParams( config );

			// Exec.
			final FijiApposeProgressListener listener = new FijiApposeProgressListener( progress, config.getName() );
			final ImagePlus[] outputs = Omnipose.omnipose( imp, params, listener );

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
}
