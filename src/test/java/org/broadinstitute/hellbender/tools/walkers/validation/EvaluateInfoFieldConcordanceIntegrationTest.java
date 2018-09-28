package org.broadinstitute.hellbender.tools.walkers.validation;

import htsjdk.variant.variantcontext.VariantContext;
import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.engine.AbstractConcordanceWalker;
import org.broadinstitute.hellbender.testutils.ArgumentsBuilder;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Path;

public class EvaluateInfoFieldConcordanceIntegrationTest extends CommandLineProgramTest {
    final double epsilon = 1e-3;

    @Test
    public void testSimple() throws Exception {
        final String inputVcf = largeFileTestDir + "VQSR/expected/chr20_tiny_tf_python_gpu2.vcf";
        final Path summary = createTempPath("summary", ".txt");
        final ArgumentsBuilder argsBuilder = new ArgumentsBuilder();
        argsBuilder.addArgument(AbstractConcordanceWalker.EVAL_VARIANTS_SHORT_NAME, inputVcf)
                .addArgument(AbstractConcordanceWalker.TRUTH_VARIANTS_LONG_NAME, inputVcf)
                .addArgument("eval-info-key", GATKVCFConstants.CNN_2D_KEY)
                .addArgument("truth-info-key", "NOVA_HISEQ_MIX_SMALL")
                .addArgument(EvaluateInfoFieldConcordance.SUMMARY_LONG_NAME, summary.toString());
        runCommandLine(argsBuilder);

        try(InfoConcordanceRecord.InfoConcordanceReader
                    reader = new InfoConcordanceRecord.InfoConcordanceReader(summary)) {
            InfoConcordanceRecord snpRecord = reader.readRecord();
            InfoConcordanceRecord indelRecord = reader.readRecord();

            Assert.assertEquals(snpRecord.getVariantType(), VariantContext.Type.SNP);
            Assert.assertEquals(indelRecord.getVariantType(), VariantContext.Type.INDEL);

            // numbers verified by manual inspection
            Assert.assertEquals(snpRecord.getMean(), 0.086470, epsilon);
            Assert.assertEquals(snpRecord.getStd(), 0.209133, epsilon);
            Assert.assertEquals(indelRecord.getMean(), 0.013632, epsilon);
            Assert.assertEquals(indelRecord.getStd(), 0.069784, epsilon);
        }
    }

    @Test
    public void test2Vcfs() throws Exception {
        final String inputVcf1 = largeFileTestDir + "VQSR/expected/chr20_tiny_tf_python_cpu.vcf";
        final String inputVcf2 = largeFileTestDir + "VQSR/expected/chr20_tiny_th_python_gpu.vcf";

        final Path summary = createTempPath("summary", ".txt");
        final ArgumentsBuilder argsBuilder = new ArgumentsBuilder();
        argsBuilder.addArgument(AbstractConcordanceWalker.EVAL_VARIANTS_SHORT_NAME, inputVcf1)
                .addArgument(AbstractConcordanceWalker.TRUTH_VARIANTS_LONG_NAME, inputVcf2)
                .addArgument("eval-info-key", GATKVCFConstants.CNN_1D_KEY)
                .addArgument("truth-info-key", "NOVA_HISEQ_MIX_1D_RAB")
                .addArgument(EvaluateInfoFieldConcordance.SUMMARY_LONG_NAME, summary.toString());
        runCommandLine(argsBuilder);

        try(InfoConcordanceRecord.InfoConcordanceReader
                    reader = new InfoConcordanceRecord.InfoConcordanceReader(summary)) {
            InfoConcordanceRecord snpRecord = reader.readRecord();
            InfoConcordanceRecord indelRecord = reader.readRecord();

            Assert.assertEquals(snpRecord.getVariantType(), VariantContext.Type.SNP);
            Assert.assertEquals(indelRecord.getVariantType(), VariantContext.Type.INDEL);

            // numbers verified by manual inspection
            Assert.assertEquals(snpRecord.getMean(), 0.000203, epsilon);
            Assert.assertEquals(snpRecord.getStd(), 0.000159, epsilon);
            Assert.assertEquals(indelRecord.getMean(), 0.000049, epsilon);
            Assert.assertEquals(indelRecord.getStd(), 0.000119, epsilon);
        }
    }
}
