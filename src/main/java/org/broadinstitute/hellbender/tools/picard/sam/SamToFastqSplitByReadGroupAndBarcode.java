package org.broadinstitute.hellbender.tools.picard.sam;

import htsjdk.samtools.*;
import htsjdk.samtools.fastq.FastqRecord;
import htsjdk.samtools.fastq.FastqWriter;
import htsjdk.samtools.fastq.FastqWriterFactory;
import htsjdk.samtools.util.*;
import org.broadinstitute.hellbender.cmdline.Argument;
import org.broadinstitute.hellbender.cmdline.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.PicardCommandLineProgram;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.ReadProgramGroup;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.runtime.ProgressLogger;

import java.io.File;
import java.util.*;

/**
 * <p/>
 * Extracts read sequences and qualities from the input SAM/BAM file and writes them into
 * the output file in Sanger fastq format.
 * See <a href="http://maq.sourceforge.net/fastq.shtml">MAQ FastQ specification</a> for details.
 * In the RC mode (default is True), if the read is aligned and the alignment is to the reverse strand on the genome,
 * the read's sequence from input sam file will be reverse-complemented prior to writing it to fastq in order restore correctly
 * the original read sequence as it was generated by the sequencer.
 */
@CommandLineProgramProperties(
        summary = "Extracts read sequences and qualities from the input SAM/BAM file and writes them into " +
                "the output file in Sanger fastq format. In the RC mode (default is True), if the read is aligned and the alignment is to the reverse strand on the genome, " +
                "the read's sequence from input SAM file will be reverse-complemented prior to writing it to fastq in order restore correctly" +
                "the original read sequence as it was generated by the sequencer.",
        oneLineSummary = "Converts a SAM/BAM file into a FASTQ",
        programGroup = ReadProgramGroup.class
)
public final class SamToFastqSplitByReadGroupAndBarcode extends PicardCommandLineProgram {

    @Argument(doc = "Input SAM/BAM file to extract reads from",
            fullName = StandardArgumentDefinitions.INPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME)
    public File INPUT;

    public boolean OUTPUT_PER_RG = true;

    @Argument(shortName="RGT", doc = "The read group tag (PU or ID) to be used to output a fastq file per read group.")
    public String RG_TAG = "PU";

    @Argument(shortName = "ODIR", doc = "Directory in which to output the fastq file(s).  Used only when OUTPUT_PER_RG is true.",
            optional = false)
    public File OUTPUT_DIR;

    @Argument(shortName = "BARCODES", doc = "Comma separated list of barcodes.",
            optional = false)
    public String BARCODES;

    @Argument(shortName = "RC", doc = "Re-reverse bases and qualities of reads with negative strand flag set before writing them to fastq",
            optional = true)
    public boolean RE_REVERSE = true;

    @Argument(shortName = "INTER", doc = "Will generate an interleaved fastq if paired, each line will have /1 or /2 to describe which end it came from")
    public boolean INTERLEAVE = false;

    @Argument(shortName = "NON_PF", doc = "Include non-PF reads from the SAM file into the output " +
            "FASTQ files. PF means 'passes filtering'. Reads whose 'not passing quality controls' " +
            "flag is set are non-PF reads.")
    public boolean INCLUDE_NON_PF_READS = false;

    @Argument(shortName = "CLIP_ATTR", doc = "The attribute that stores the position at which " +
            "the SAM record should be clipped", optional = true)
    public String CLIPPING_ATTRIBUTE;

    @Argument(shortName = "CLIP_ACT", doc = "The action that should be taken with clipped reads: " +
            "'X' means the reads and qualities should be trimmed at the clipped position; " +
            "'N' means the bases should be changed to Ns in the clipped region; and any " +
            "integer means that the base qualities should be set to that value in the " +
            "clipped region.", optional = true)
    public String CLIPPING_ACTION;

    @Argument(shortName = "R1_TRIM", doc = "The number of bases to trim from the beginning of read 1.")
    public int READ1_TRIM = 0;

    @Argument(shortName = "R1_MAX_BASES", doc = "The maximum number of bases to write from read 1 after trimming. " +
            "If there are fewer than this many bases left after trimming, all will be written.  If this " +
            "value is null then all bases left after trimming will be written.", optional = true)
    public Integer READ1_MAX_BASES_TO_WRITE;

    @Argument(shortName = "R2_TRIM", doc = "The number of bases to trim from the beginning of read 2.")
    public int READ2_TRIM = 0;

    @Argument(shortName = "R2_MAX_BASES", doc = "The maximum number of bases to write from read 2 after trimming. " +
            "If there are fewer than this many bases left after trimming, all will be written.  If this " +
            "value is null then all bases left after trimming will be written.", optional = true)
    public Integer READ2_MAX_BASES_TO_WRITE;

    @Argument(doc = "If true, include non-primary alignments in the output.  Support of non-primary alignments in SamToFastq " +
            "is not comprehensive, so there may be exceptions if this is set to true and there are paired reads with non-primary alignments.")
    public boolean INCLUDE_NON_PRIMARY_ALIGNMENTS = false;

    @Override
    protected Object doWork() {
        IOUtil.assertFileIsReadable(INPUT);
        final SamReader reader = SamReaderFactory.makeDefault().referenceSequence(REFERENCE_SEQUENCE).open(INPUT);
        final Map<String, SAMRecord> firstSeenMates = new HashMap<>();
        final FastqWriterFactory factory = new FastqWriterFactory();
        factory.setCreateMd5(CREATE_MD5_FILE);
        final List<String> barcodes = Arrays.asList(BARCODES.split(","));
        final Map<String, FastqWriters> writers = generateWriters(reader.getFileHeader().getReadGroups(), factory, barcodes);

        final ProgressLogger progress = new ProgressLogger(logger);
        for (final SAMRecord currentRecord : reader) {
            if (currentRecord.isSecondaryOrSupplementary() && !INCLUDE_NON_PRIMARY_ALIGNMENTS)
                continue;

            // Skip non-PF reads as necessary
            if (currentRecord.getReadFailsVendorQualityCheckFlag() && !INCLUDE_NON_PF_READS)
                continue;

            final String sampleBarcode = currentRecord.getStringAttribute("BC");
            final String writerKey = currentRecord.getReadGroup() + "-" + sampleBarcode;
            final FastqWriters fq = writers.get(writerKey);
            if (fq == null) {
                throw new GATKException("No writer available for key " + writerKey);
            }
            if (currentRecord.getReadPairedFlag()) {
                final String currentReadName = currentRecord.getReadName();
                final SAMRecord firstRecord = firstSeenMates.remove(currentReadName);
                if (firstRecord == null) {
                    firstSeenMates.put(currentReadName, currentRecord);
                } else {
                    assertPairedMates(firstRecord, currentRecord);

                    final SAMRecord read1 =
                            currentRecord.getFirstOfPairFlag() ? currentRecord : firstRecord;
                    final SAMRecord read2 =
                            currentRecord.getFirstOfPairFlag() ? firstRecord : currentRecord;
                    writeRecord(read1, 1, fq.getFirstOfPair(), READ1_TRIM, READ1_MAX_BASES_TO_WRITE, fq.barcode);
                    final FastqWriter secondOfPairWriter = fq.getSecondOfPair();
                    if (secondOfPairWriter == null) {
                        throw new UserException("Input contains paired reads but no SECOND_END_FASTQ specified.");
                    }
                    writeRecord(read2, 2, secondOfPairWriter, READ2_TRIM, READ2_MAX_BASES_TO_WRITE, fq.barcode);
                }
            } else {
                writeRecord(currentRecord, null, fq.getUnpaired(), READ1_TRIM, READ1_MAX_BASES_TO_WRITE, fq.barcode);
            }

            progress.record(currentRecord);
        }

        CloserUtil.close(reader);

        // Close all the fastq writers being careful to close each one only once!
        for (final FastqWriters writerMapping : new HashSet<>(writers.values())) {
            writerMapping.closeAll();
        }

        if (firstSeenMates.size() > 0) {
            SAMUtils.processValidationError(new SAMValidationError(SAMValidationError.Type.MATE_NOT_FOUND,
                    "Found " + firstSeenMates.size() + " unpaired mates", null), VALIDATION_STRINGENCY);
        }

        return null;
    }

    /**
     * Generates the writers for the given read groups or, if we are not emitting per-read-group, just returns the single set of writers.
     */
    private Map<String, FastqWriters> generateWriters(final List<SAMReadGroupRecord> samReadGroupRecords,
                                                                  final FastqWriterFactory factory,
                                                                  final List<String> barcodes) {

        final Map<String, FastqWriters> writerMap = new HashMap<>();

            // When we're creating a fastq-group per readgroup, by convention we do not emit a special fastq for unpaired reads.
        for (final SAMReadGroupRecord rg : samReadGroupRecords) {
            for (final String barcode : barcodes) {
                final FastqWriter firstOfPairWriter = factory.newWriter(makeReadGroupFile(rg, "_RA", barcode));
                final FastqWriter barcodeWriter = factory.newWriter(makeReadGroupFile(rg, "_I1", barcode));
                // Create this writer on-the-fly; if we find no second-of-pair reads, don't bother making a writer (or delegating,
                // if we're interleaving).
                final Lazy<FastqWriter> lazySecondOfPairWriter = new Lazy<>(new Lazy.LazyInitializer<FastqWriter>() {
                    @Override
                    public FastqWriter make() {
                        return INTERLEAVE ? firstOfPairWriter : factory.newWriter(makeReadGroupFile(rg, "_2", barcode));
                    }
                });
                final String writerKey = rg + "-" + barcode;
                logger.info("Generating writer for " + writerKey);
                writerMap.put(writerKey, new FastqWriters(firstOfPairWriter, lazySecondOfPairWriter, firstOfPairWriter, barcodeWriter));
            }
        }
        return writerMap;
    }

    private File makeReadGroupFile(final SAMReadGroupRecord readGroup, final String preExtSuffix, final String barcode) {
        String fileName = null;
        if (RG_TAG.equalsIgnoreCase("PU")){
            fileName = readGroup.getPlatformUnit();
        } else if (RG_TAG.equalsIgnoreCase("ID")){
            fileName = readGroup.getReadGroupId();
        }
        fileName += "_" + barcode;
        if (fileName == null) {
            throw new UserException("The selected RG_TAG: "+RG_TAG+" is not present in the bam header.");
        }
        fileName = IOUtil.makeFileNameSafe(fileName);
        if (preExtSuffix != null) fileName += preExtSuffix;
        fileName += ".fastq.gz";

        final File result = (OUTPUT_DIR != null)
                ? new File(OUTPUT_DIR, fileName)
                : new File(fileName);
        IOUtil.assertFileIsWritable(result);
        return result;
    }

    private void writeRecord(final SAMRecord read, final Integer mateNumber, final FastqWriter writer,
                             final int basesToTrim, final Integer maxBasesToWrite, final FastqWriter barcode) {
        final String seqHeader;
        if (mateNumber == null) {
            seqHeader = read.getReadName();
        } else {
            if (mateNumber == 1) {
                seqHeader = read.getReadName() + " 1:N:0";
            } else {
                seqHeader = read.getReadName() + " 3:N:0";
            }
        }
        final String indexReadHeader = read.getReadName() + " 2:N:0";
        String readString = read.getReadString();
        String baseQualities = read.getBaseQualityString();

        // If we're clipping, do the right thing to the bases or qualities
        if (CLIPPING_ATTRIBUTE != null) {
            final Integer clipPoint = (Integer) read.getAttribute(CLIPPING_ATTRIBUTE);
            if (clipPoint != null) {
                if (CLIPPING_ACTION.equalsIgnoreCase("X")) {
                    readString = clip(readString, clipPoint, null,
                            !read.getReadNegativeStrandFlag());
                    baseQualities = clip(baseQualities, clipPoint, null,
                            !read.getReadNegativeStrandFlag());

                } else if (CLIPPING_ACTION.equalsIgnoreCase("N")) {
                    readString = clip(readString, clipPoint, 'N',
                            !read.getReadNegativeStrandFlag());
                } else {
                    final char newQual = SAMUtils.phredToFastq(
                            new byte[]{(byte) Integer.parseInt(CLIPPING_ACTION)}).charAt(0);
                    baseQualities = clip(baseQualities, clipPoint, newQual,
                            !read.getReadNegativeStrandFlag());
                }
            }
        }
        if (RE_REVERSE && read.getReadNegativeStrandFlag()) {
            readString = SequenceUtil.reverseComplement(readString);
            baseQualities = StringUtil.reverseString(baseQualities);
        }
        if (basesToTrim > 0) {
            readString = readString.substring(basesToTrim);
            baseQualities = baseQualities.substring(basesToTrim);
        }

        if (maxBasesToWrite != null && maxBasesToWrite < readString.length()) {
            readString = readString.substring(0, maxBasesToWrite);
            baseQualities = baseQualities.substring(0, maxBasesToWrite);
        }

        if (mateNumber != null && mateNumber == 1) {
            final String tenxBC = read.getStringAttribute("RX");
            final String tenxBCQ = read.getStringAttribute("QX");
            final String fillerBases = "AAAAAAA";
            final String fillerQuals = "AAAAAAA";
            readString = tenxBC + fillerBases + readString;
            baseQualities = tenxBCQ + fillerQuals + baseQualities;
        }

        writer.write(new FastqRecord(seqHeader, readString, "", baseQualities));

        if (mateNumber != null && mateNumber == 1) {
            barcode.write(new FastqRecord(indexReadHeader, read.getStringAttribute("BC"), "", read.getStringAttribute("QT")));
        }

    }

    /**
     * Utility method to handle the changes required to the base/quality strings by the clipping
     * parameters.
     *
     * @param src         The string to clip
     * @param point       The 1-based position of the first clipped base in the read
     * @param replacement If non-null, the character to replace in the clipped positions
     *                    in the string (a quality score or 'N').  If null, just trim src
     * @param posStrand   Whether the read is on the positive strand
     * @return String       The clipped read or qualities
     */
    private String clip(final String src, final int point, final Character replacement, final boolean posStrand) {
        final int len = src.length();
        String result = posStrand ? src.substring(0, point - 1) : src.substring(len - point + 1);
        if (replacement != null) {
            if (posStrand) {
                for (int i = point; i <= len; i++) {
                    result += replacement;
                }
            } else {
                for (int i = 0; i <= len - point; i++) {
                    result = replacement + result;
                }
            }
        }
        return result;
    }

    private void assertPairedMates(final SAMRecord record1, final SAMRecord record2) {
        if (!(record1.getFirstOfPairFlag() && record2.getSecondOfPairFlag() ||
                record2.getFirstOfPairFlag() && record1.getSecondOfPairFlag())) {
            throw new GATKException("Illegal mate state: " + record1.getReadName());
        }
    }

    /**
     * Put any custom command-line validation in an override of this method.
     * clp is initialized at this point and can be used to print usage and access argv.
     * Any options set by command-line parser can be validated.
     *
     * @return null if command line is valid.  If command line is invalid, returns an array of error
     * messages to be written to the appropriate place.
     */
    protected String[] customCommandLineValidation() {

        if ((CLIPPING_ATTRIBUTE != null && CLIPPING_ACTION == null) ||
                (CLIPPING_ATTRIBUTE == null && CLIPPING_ACTION != null)) {
            return new String[]{
                    "Both or neither of CLIPPING_ATTRIBUTE and CLIPPING_ACTION should be set."};
        }

        if (CLIPPING_ACTION != null) {
            if (CLIPPING_ACTION.equals("N") || CLIPPING_ACTION.equals("X")) {
                // Do nothing, this is fine
            } else {
                try {
                    Integer.parseInt(CLIPPING_ACTION);
                } catch (NumberFormatException nfe) {
                    return new String[]{"CLIPPING ACTION must be one of: N, X, or an integer"};
                }
            }
        }

        if ((OUTPUT_PER_RG && OUTPUT_DIR == null) || ((!OUTPUT_PER_RG) && OUTPUT_DIR != null)) {
            return new String[]{
                    "If OUTPUT_PER_RG is true, then OUTPUT_DIR should be set. " +
                            "If "};
        }

        if (OUTPUT_PER_RG) {
            if (RG_TAG == null) {
                return new String[]{"If OUTPUT_PER_RG is true, then RG_TAG should be set."};
            } else if (! (RG_TAG.equalsIgnoreCase("PU") || RG_TAG.equalsIgnoreCase("ID")) ){
                return new String[]{"RG_TAG must be: PU or ID"};
            }
        }
        return null;
    }

    /**
     * A collection of {@link FastqWriter}s for particular types of reads.
     * <p/>
     * Allows for lazy construction of the second-of-pair writer, since when we are in the "output per read group mode", we only wish to
     * generate a second-of-pair fastq if we encounter a second-of-pair read.
     */
    static class FastqWriters {
        private final FastqWriter firstOfPair, unpaired, barcode;
        private final Lazy<FastqWriter> secondOfPair;

        /** Constructor if the consumer wishes for the second-of-pair writer to be built on-the-fly. */
        private FastqWriters(final FastqWriter firstOfPair, final Lazy<FastqWriter> secondOfPair, final FastqWriter unpaired, final FastqWriter barcode) {
            this.firstOfPair = firstOfPair;
            this.unpaired = unpaired;
            this.secondOfPair = secondOfPair;
            this.barcode = barcode;
        }

        /** Simple constructor; all writers are pre-initialized.. */
        private FastqWriters(final FastqWriter firstOfPair, final FastqWriter secondOfPair, final FastqWriter unpaired, final FastqWriter barcode) {
            this(firstOfPair, new Lazy<>(new Lazy.LazyInitializer<FastqWriter>() {
                @Override
                public FastqWriter make() {
                    return secondOfPair;
                }
            }), unpaired, barcode);
        }

        public FastqWriter getFirstOfPair() {
            return firstOfPair;
        }

        public FastqWriter getSecondOfPair() {
            return secondOfPair.get();
        }

        public FastqWriter getUnpaired() {
            return unpaired;
        }

        public void closeAll() {
            final Set<FastqWriter> fastqWriters = new HashSet<>();
            fastqWriters.add(firstOfPair);
            fastqWriters.add(unpaired);
            fastqWriters.add(barcode);
            // Make sure this is a no-op if the second writer was never fetched.
            if (secondOfPair.isInitialized()) fastqWriters.add(secondOfPair.get());
            for (final FastqWriter fastqWriter : fastqWriters) {
                fastqWriter.close();
            }
        }
    }
}
