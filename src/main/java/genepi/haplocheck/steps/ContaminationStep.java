package genepi.haplocheck.steps;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import com.google.common.math.Quantiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import core.SampleFile;
import cloudgene.sdk.internal.WorkflowContext;
import cloudgene.sdk.internal.WorkflowStep;
import genepi.haplocheck.steps.contamination.ContaminationDetection;
import genepi.haplocheck.steps.contamination.ContaminationDetection.Status;
import genepi.haplocheck.steps.contamination.HaplogroupClassifier;
import genepi.haplocheck.steps.contamination.VariantSplitter;
import genepi.haplocheck.steps.contamination.objects.ContaminationObject;
import genepi.haplocheck.steps.report.ReportGenerator;
import genepi.haplocheck.util.Utils;
import importer.VcfImporter;
import phylotree.Phylotree;
import phylotree.PhylotreeManager;
import util.ExportUtils;
import vcf.Sample;

public class ContaminationStep extends WorkflowStep {

	@Override
	public boolean run(WorkflowContext context) {
		return detectContamination(context);

	}

	private boolean detectContamination(WorkflowContext context) {

		try {
			Phylotree phylotree = PhylotreeManager.getInstance().getPhylotree("phylotree17.xml", "weights17.txt");

			String input = context.get("files");
			String output = context.getConfig("output");
			String outputReport = context.getConfig("outputReport");
			String outputHsd = context.getConfig("outputHsd");

			Collection<File> out = Utils.getVcfFiles(input);

			if (out.size() > 1) {
				context.endTask("Currently only single VCF file upload is supported!", WorkflowContext.ERROR);
			}

			File file = out.iterator().next();

			context.beginTask("Check for Contamination.. ");

			VariantSplitter splitter = new VariantSplitter();

			VcfImporter reader = new VcfImporter();

			context.updateTask("Load file...", WorkflowContext.RUNNING);
			HashMap<String, Sample> mutationServerSamples = reader.load(file, false);

			context.updateTask("Split Profile into Major/Minor Profile...", WorkflowContext.RUNNING);
			ArrayList<String> profiles = splitter.split(mutationServerSamples);

			context.updateTask("Classify Haplogroups...", WorkflowContext.RUNNING);
			HaplogroupClassifier classifier = new HaplogroupClassifier();
			SampleFile haplogrepSamples = classifier.calculateHaplogroups(phylotree, profiles);

			ContaminationDetection contamination = new ContaminationDetection();
			context.updateTask("Detect Contamination...", WorkflowContext.RUNNING);

			ArrayList<ContaminationObject> result = contamination.detect(mutationServerSamples,
					haplogrepSamples.getTestSamples());
			

			ExportUtils.createHsdInput(haplogrepSamples.getTestSamples(), outputHsd);

			context.updateTask("Write Contamination Report...", WorkflowContext.RUNNING);
			
			contamination.writeTextualReport(output, result);
			ReportGenerator generator = new ReportGenerator();
			generator.setContamination(getJson(result));
			generator.setSummary(getSummary(result));
			generator.generate(outputReport);

			context.endTask("Execution successful.", WorkflowContext.OK);
			return true;

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
	}

	public String getJson(ArrayList<ContaminationObject> contaminationList) throws IOException {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		return gson.toJson(contaminationList);
	}

	private String getSummary(ArrayList<ContaminationObject> contaminationList) throws IOException {
		int countYes = 0;
		int countNo = 0;
		ArrayList<Integer> coverageList = new ArrayList<Integer>();

		for (ContaminationObject cont : contaminationList) {

			if (cont.getStatus() == Status.YES) {
				countYes++;
			} else if (cont.getStatus() == Status.NO) {
				countNo++;
			}
			coverageList.add(cont.getSampleMeanCoverage());
		}

		JsonObject result = new JsonObject();
		result.add("Yes", new JsonPrimitive(countYes));
		result.add("No", new JsonPrimitive(countNo));
		double coverageMedian = com.google.common.math.Quantiles.median().compute(coverageList);
		double percentile25 = Quantiles.percentiles().index(25).compute(coverageList);
		double percentile75 = Quantiles.percentiles().index(75).compute(coverageList);
		double IQR = percentile75 - percentile25;
		result.add("Coverage", new JsonPrimitive(coverageMedian));
		result.add("Q1", new JsonPrimitive(percentile25));
		result.add("Q3", new JsonPrimitive(percentile75));

		return result.toString();

	}

}
