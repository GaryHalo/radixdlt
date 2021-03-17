package com.radixdlt.cloud;

import com.radixdlt.cli.OutputCapture;
import com.radixdlt.cli.RadixCLI;
import com.radixdlt.utils.AWSSecretManager;
import com.radixdlt.utils.AWSSecretsOutputOptions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class AWSSecrets {

	private static final Boolean DEFAULT_ENABLE_AWS_SECRETS = false;
	private static final Boolean DEFAULT_RECREATE_AWS_SECRETS = false;
	private static final String DEFAULT_NETWORK_NAME = "testnet";

	public static void main(String[] args) {

		Options options = new Options();
		options.addOption("h", "help", false, "Show usage information (this message)");
		options.addOption("n", "node-name", true, "Name of the node");
		options.addOption("p", "password", true, "Password for the keystore");
		options.addOption("as", "enable-aws-secrets", true, "Store as AWS Secrets(default: " + DEFAULT_ENABLE_AWS_SECRETS + ")");
		options.addOption("rs", "recreate-aws-secrets", true, "Recreate AWS Secrets(default: " + DEFAULT_RECREATE_AWS_SECRETS + ")");
		options.addOption("k", "network-name", true, "Network name(default: " + DEFAULT_NETWORK_NAME + ")");

		CommandLineParser parser = new DefaultParser();
		try {
			CommandLine cmd = parser.parse(options, args);
			if (!cmd.getArgList().isEmpty()) {
				System.err.println("Extra arguments: " + cmd.getArgList().stream().collect(Collectors.joining(" ")));
				usage(options);
				return;
			}

			if (cmd.hasOption('h')) {
				usage(options);
				return;
			}

			final String nodeName = Optional.ofNullable(cmd.getOptionValue("n"))
				.orElseThrow(() -> new IllegalArgumentException("Must specify node name"));
			final String password = Optional.ofNullable(cmd.getOptionValue("p"))
				.orElseThrow(() -> new IllegalArgumentException("Must specify password for the store"));

			final String networkName = getOption(cmd, 'n').orElse(DEFAULT_NETWORK_NAME);
			final boolean enableAwsSecrets = Boolean.parseBoolean(cmd.getOptionValue("as"));
			final boolean recreateAwsSecrets = Boolean.parseBoolean(cmd.getOptionValue("rs"));

			final AWSSecretsOutputOptions awsSecretsOutputOptions = new AWSSecretsOutputOptions(enableAwsSecrets, recreateAwsSecrets, networkName);
			final String keyStoreName = String.format("%s-%s.ks", networkName, nodeName);
			final String keyFileSecretName = String.format("%s/%s.ks", networkName, keyStoreName);
			try (OutputCapture capture = OutputCapture.startStderr()) {
				RadixCLI.main(new String[]{"generate-validator-key", "-k=" + keyStoreName, "-n=" + keyStoreName, "-p=nopass"});
				final String output = capture.stop();
				Path keyFilePath = Paths.get(keyStoreName);
				Map<String, Object> keyFileAwsSecret = new HashMap<>();
				try {
					byte[] data = Files.readAllBytes(keyFilePath);
					keyFileAwsSecret.put("key", data);
				} catch (IOException e) {
					throw new IllegalStateException("While reading validator keys", e);
				}
				writeBinaryAWSSecret(keyFileAwsSecret, keyFileSecretName, awsSecretsOutputOptions, true,true);
			}


		} catch (ParseException e) {
			e.printStackTrace();
		}
	}

	private static void usage(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp(AWSSecrets.class.getSimpleName(), options, true);
	}

	private static Optional<String> getOption(CommandLine cmd, char opt) {
		String value = cmd.getOptionValue(opt);
		return Optional.ofNullable(value);
	}

	private static void writeBinaryAWSSecret(Map<String, Object> awsSecret, String secretName, AWSSecretsOutputOptions awsSecretsOutputOptions, boolean compress, boolean binarySecret){
		if (AWSSecretManager.awsSecretExists(secretName)) {
			AWSSecretManager.updateAWSSecret(awsSecret, secretName, awsSecretsOutputOptions, compress, binarySecret);
		} else {
			AWSSecretManager.createAWSSecret(awsSecret, secretName, awsSecretsOutputOptions, compress, binarySecret);
		}
	}

}
