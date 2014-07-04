package au.edu.wehi.idsv;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamPairUtil.PairOrientation;
import htsjdk.samtools.fastq.FastqWriterFactory;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder;
import htsjdk.variant.vcf.VCFHeader;

import java.io.File;
import java.io.IOException;

import picard.cmdline.Option;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.Usage;
import au.edu.wehi.idsv.vcf.VcfConstants;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

public class GenerateDirectedBreakpoints extends CommandLineProgram {

    private static final String PROGRAM_VERSION = "0.1";
    @Usage
    public String USAGE = getStandardUsagePreamble() + "Generates putative breakend sequences "
    		+ PROGRAM_VERSION;
    
    @Option(doc = "Coordinate sorted input file containing reads supporting putative structural variations",
            optional = false,
            shortName = "SV")
    public File SV_INPUT;
    @Option(doc = "DP and OEA read pairs sorted by coordinate of mapped mate read.",
            optional = false,
            shortName = "MCI")
    public File MATE_COORDINATE_INPUT;
    @Option(doc = "Directed single-ended breakpoints. A placeholder contig is output as the breakpoint partner.",
            optional = false,
            shortName=StandardOptionDefinitions.OUTPUT_SHORT_NAME)
    public File VCF_OUTPUT;
    @Option(doc = "FASTQ of reference strand sequences of putative breakpoints excluding anchored bases. These sequences are used to align breakpoints",
            optional = false,
            shortName = "FQ")
    public File FASTQ_OUTPUT;
    @Option(doc = "Picard metrics file generated by ExtractEvidence",
            optional = true)
    public File METRICS = null;
    @Option(doc = "Reference used for alignment",
            optional = false)
    public File REFERENCE;
    @Option(doc = "Minimum alignment mapq",
    		optional=true)
    public int MIN_MAPQ = 5;
    @Option(doc = "Minimum length of a breakend to be considered for realignment",
    		optional=true,
    		shortName="LEN")
    public int MIN_BREAKEND_REALIGN_LENGTH = 25;
    @Option(doc = "Minimum alignment percent identity to reference. Takes values in the range 0-100.",
    		optional=true)
    public float MIN_PERCENT_IDENTITY = 95;
    @Option(doc = "Minimum average base quality score of soft clipped sequence",
    		optional=true)
    public float MIN_LONG_SC_BASE_QUALITY = 5;
    @Option(doc = "k-mer used for de bruijn graph construction",
    		optional=true,
    		shortName="K")
    public int KMER = 25;
    private Log log = Log.getInstance(GenerateDirectedBreakpoints.class);
    @Override
	protected int doWork() {
    	try {
    		if (METRICS == null) {
    			METRICS = FileNamingConvention.getMetrics(SV_INPUT);
    		}
    		IOUtil.assertFileIsReadable(METRICS);
    		IOUtil.assertFileIsReadable(SV_INPUT);
    		IOUtil.assertFileIsReadable(MATE_COORDINATE_INPUT);
    		
    		IOUtil.assertFileIsWritable(VCF_OUTPUT);
    		IOUtil.assertFileIsWritable(FASTQ_OUTPUT);
    		
    		final ProgressLogger progress = new ProgressLogger(log);
	    	final SamReader reader = getSamReaderFactory().open(SV_INPUT);
	    	final SamReader mateReader = getSamReaderFactory().open(MATE_COORDINATE_INPUT);
	    	final ProcessingContext processContext = getContext(REFERENCE, SV_INPUT);
	    	final SAMSequenceDictionary dictionary = processContext.getDictionary();
	    	final ReferenceSequenceFile reference = processContext.getReference();
	    	
	    	if (processContext.getMetrics().getPairOrientation() != null && processContext.getMetrics().getPairOrientation() != PairOrientation.FR) {
	    		// TODO: handle reads other than Illumina paired end reads
	    		throw new IllegalArgumentException(String.format("Read pair %s orientation not yet implemented.", processContext.getMetrics().getPairOrientation()));
			}
	    	
			final PeekingIterator<SAMRecord> iter = Iterators.peekingIterator(reader.iterator());
			final PeekingIterator<SAMRecord> mateIter = Iterators.peekingIterator(mateReader.iterator());
			final FastqBreakpointWriter fastqWriter = new FastqBreakpointWriter(new FastqWriterFactory().newWriter(FASTQ_OUTPUT));
			final VariantContextWriter vcfWriter = new VariantContextWriterBuilder()
				.setOutputFile(VCF_OUTPUT)
				.setReferenceDictionary(dictionary)
				.build();
			final VCFHeader vcfHeader = new VCFHeader();
			vcfHeader.setSequenceDictionary(processContext.getDictionary());
			VcfConstants.addHeaders(vcfHeader);
			vcfWriter.writeHeader(vcfHeader);
			
			DirectedEvidenceIterator dei = new DirectedEvidenceIterator(processContext, iter, mateIter, null, null);
			ReadEvidenceAssembler assembler = new DeBruijnAnchoredAssembler(processContext, KMER);
			while (dei.hasNext()) {
				DirectedEvidence readEvidence = dei.next();
				progress.record(reference.getSequenceDictionary().getSequence(readEvidence.getBreakendSummary().referenceIndex).getSequenceName(), readEvidence.getBreakendSummary().start);
				// Need to process assembly evidence first since assembly calls are made when the
				// evidence falls out of scope so processing a given position will emit evidence
				// for a previous position (for it is only at this point we know there is no more
				// evidence for the previous position).
				processAssemblyEvidence(assembler.addEvidence(readEvidence), fastqWriter, vcfWriter);
				if (readEvidence instanceof SoftClipEvidence) {
					SoftClipEvidence sce = (SoftClipEvidence)readEvidence;
					if (sce.getMappingQuality() >= MIN_MAPQ &&
							sce.getSoftClipLength() >= MIN_BREAKEND_REALIGN_LENGTH &&
							sce.getAlignedPercentIdentity() >= MIN_PERCENT_IDENTITY &&
							sce.getAverageClipQuality() >= MIN_LONG_SC_BASE_QUALITY) {
						fastqWriter.write(sce);
					}
				}
			}
			processAssemblyEvidence(assembler.endOfEvidence(), fastqWriter, vcfWriter);
	    	fastqWriter.close();
	    	vcfWriter.close();
	    	reader.close();
	    	mateReader.close();
    	} catch (IOException e) {
    		log.error(e);
    		throw new RuntimeException(e);
    	}
        return 0;
    }
    private void processAssemblyEvidence(Iterable<VariantContextDirectedBreakpoint> evidenceList, FastqBreakpointWriter fastqWriter, VariantContextWriter vcfWriter) {
    	if (evidenceList != null) {
	    	for (VariantContextDirectedBreakpoint a : evidenceList) {
	    		if (a != null) {
	    			if (a.getBreakpointSequence().length >= MIN_BREAKEND_REALIGN_LENGTH) {
		    			fastqWriter.write(a);
			    		vcfWriter.add(a);
	    			}
	    		}
	    	}
    	}
    }
	public static void main(String[] argv) {
        System.exit(new GenerateDirectedBreakpoints().instanceMain(argv));
    }
}
