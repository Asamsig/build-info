package build

import build.specs.BuildInfoTestSpec
import build.specs.DeploymentTestSpec
import build.specs.LicensesTestSpec
import docker.RunWithDocker
import groovy.io.FileType
import org.jfrog.artifactory.client.Artifactory
import org.jfrog.artifactory.client.ArtifactoryClient
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import utils.*

/**
 * This class represents a suite of tests that covers the "build process" together with BuildInfo extractors such as
 * Gradle/Maven/Ivy and Generic
 *
 * The main flow is to create {@link TestProfile} instances according to test configuration files under
 * resources/testConfigurations folder.
 * The next step will be to launch all of them and fetch the build info from Artifactory.
 *
 *
 * @author Lior Hasson
 */

@RunWith(Suite)
@Suite.SuiteClasses([DeploymentTestSpec.class, BuildInfoTestSpec.class, LicensesTestSpec.class])
@RunWithDocker(
    registry = "docker.jfrog.info", imageId = "artifactory-pro", repo = "artifactory",
    containerPort = 8081, hostPort = 8888
)

class BuildTestBaseSpec extends AbstractJUnitTest{
    private static final Logger logger = LoggerFactory.getLogger(BuildTestBaseSpec.class);
    private static def config = TestsConfig.getInstance().config
    static List<TestProfile> testProfiles = new ArrayList<TestProfile>()
    static Artifactory artifactory

    @BeforeClass
    static void setup() {
        assert config
        assert config.artifactory.url
        assert config.artifactory.username
        assert config.artifactory.password

        waitForArtifactoryToLoad()
        def dir = new File(((String) config.testConfigurationsPath))
        //Iterates over all the test Config files to create test profile from them
        dir.eachFileRecurse (FileType.FILES) { file ->
            def builder = new TestProfileBuilder(config, file, artifactory)
            testProfiles.addAll(builder
                    .splitByProperty('buildLauncher.buildToolVersions')
                    .splitByProperty('buildLauncher.projectPath')
                    .splitByProperty('buildLauncher.mavenHome')
                    .build()
            )
        }

        //Prepare the test profile information, include run build tool processes
        testProfiles.each {testProfile ->
            testProfile.buildName = testProfile.buildProperties.get(TestConstants.buildName)
            testProfile.buildNumber = testProfile.buildProperties.get(TestConstants.buildNumber)
            testProfile.launch()

            //Exception here will crashes the all tests
            try{
                testProfile.buildInfo = TestUtils.getBuildInfo(testProfile.artifactory, testProfile.buildName, testProfile.buildNumber)
            }
            catch (any){
                logger.error("Get buildInfo error: ${any.getMessage()}")
            }
        }
    }

    /**
     * Clean all the data (repositories + builds) that the tests created
     */
    @AfterClass
    static void cleanup() {
        try {
            testProfiles.each {
                String repoName = it.buildProperties.get(TestConstants.repoKey)
                String buildName = it.buildProperties.get(TestConstants.buildName)
                String buildNumber = it.buildProperties.get(TestConstants.buildNumber)
                try {
                    TestUtils.deleteBuildFromArtifactory(it.artifactory, buildName, buildNumber)
                    TestUtils.deleteRepository(it.artifactory, repoName)
                }
                catch (any){}
            }
        }
        finally {
            if(artifactory) {
                artifactory.close()
            }
        }
    }

    /**
     * Wait for Artifactory to be available (ping), and add the a pro license to it. (the license is for all the REST
     *  calls that most of them are only with pro license)
     *
     */
    static def waitForArtifactoryToLoad() {
        artifactory = ArtifactoryClient.create(
                config.artifactory.url,
                config.artifactory.username,
                config.artifactory.password
        )

        def retries = 40
        String message = 'Artifactory is down!'
        boolean isUp = false
        while (retries > 0) {
            retries--
            TestUtils.saveLicense(artifactory)
            if (artifactory.system().ping()) {
                message = 'Artifactory is fully UP!'
                isUp = true
                break
            }
            sleep 2000
        }
        println message
        if(!isUp){
            logger.error("$message")
            throw new Exception("$message")
        }
    }
}
