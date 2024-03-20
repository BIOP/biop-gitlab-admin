// https://mvnrepository.com/artifact/com.konghq/unirest-java
@Grapes([
    @Grab(group='com.konghq', module='unirest-java', version='3.14.5'), //4.0.0-RC2
    @Grab(group='org.apache.directory.api', module='api-all', version='2.1.0')
])

#@ String (value="firstname.lastname@epfl.ch") user_email
#@ String (value="The Project Name") project_name

#@ Boolean(value=false) dryRun_status

// Hello
def a = 1
UserManagerGit.dryRun = dryRun_status

def umg = new UserManagerGit(user_email, project_name)

return


class UserManagerGit{
	static String glToken
	static boolean dryRun
	private String rawProjectName
	private String projectName

	private String userGroup
	private String userEmail
	
	private String omeroDataID
	
	private static File biopDataFolder = new File ("\\\\sv-nas1.rcp.epfl.ch\\ptbiop-raw\\public\\")
	private static File localGitReposFolder = new File ("D:\\gitlab-ipa-projects")
	
	// Read the GitLab API from a .gitlab file made by the user
	static {	
		def creds = new File ( System.getProperty("user.home") + "/.gitlab" )
		def lines = creds.readLines()
		this.glToken = lines[0]
	}
	
	
	public UserManagerGit( String userEmail, String projectName ) {
		this.userEmail = userEmail
		this.rawProjectName = projectName
		
		// Sanitize project name to begin with
	    this.projectName = Utilities.sanitize( projectName )
    	IJ.log( "Preparing project: '${this.projectName}'" )
    	
    	// Fetch the group of the user
    	def group = Utilities.getUserGroup( userEmail )
    	
    	// Chop the email to use it for the group
    	this.userGroup = userEmail.split("@")[0] + "_" + group
    	
    	IJ.log( "User group is: '${this.userGroup}'" )
    	
    	// Create the GitLab Entry
    	def gitlabresults = GitLab.createGitLabProject( this.projectName, this.userGroup, this.userEmail )
    	IJ.log( "The project is: " )
    	IJ.log( ""+gitlabresults['project'] )
    	
    	// Create the folders
    	def projectDataFolder = Utilities.createProjectDataFolder( gitlabresults['project'] )
    	
    	def repoUrl = dryRun ? "https://biop.epfl.ch" : gitlabresults['project']['http_url_to_repo']
    	// Add shortcut to GitLab
    	Utilities.createInternetShortcut("GitLab to ${this.projectName}", projectDataFolder, repoUrl )
    	
    	def localRepo = Utilities.initialiseLocalGitandPush( gitlabresults['project'], gitlabresults['analyst'] )
    		
    	Utilities.createShorcut( projectDataFolder , localRepo)

	}

	
	// Helper class for making simple requests to the GitLab API and returning meaningful objects
	class GitLab {
		static String gitLabAPI = "https://gitlab.epfl.ch/api/v4"
		static int biopGroupId = 5110 // ID of the BIOP group in GitLab
		
		static {
			Unirest.config().setDefaultHeader( "PRIVATE-TOKEN", UserManagerGit.glToken )
			Unirest.config().setDefaultHeader( "Content-Type", "application/json" )
		}
		
		static createGitLabProject( String projectName, String groupName, String userEmail ) {
			// First get or create the group as needed
			String groupID = getGitLabSubGroup( groupName )
			IJ.log( "GitLab Group ID is '$groupID'" )
			
			// Get current IPA user
			def ipaUserQuery = Unirest.get( this.gitLabAPI+"/user/" ).asJson()
			def ipaUserResult = getResult( ipaUserQuery )
			def ipaUserID = ipaUserResult['id']
						
			def newProjectQuery = Unirest.post( this.gitLabAPI+"/projects" )
											.queryString("name", projectName.replace( "-", " " ) )
											.queryString("description", projectName )
											.queryString("path", projectName.toLowerCase().replaceAll(" ", "-" ) )
											.queryString("namespace_id", groupID )
											.queryString("auto_devops_enabled", false)
											.asJson()
				
			def projectResult = getResult( newProjectQuery )
			// It could happen that it worked OR that the project already exists, 
			// in case it already exists, we can try to query the project ID for later anyway
			if( projectResult.has("message") ) {
				def message = projectResult['message']
				IJ.log( "Careful, project creation failed with a message: ${message.toString()}. Trying to get project id in case it already exists" )
				def projectQuery = Unirest.get( this.gitLabAPI+"/projects" )
											.queryString("search", projectName.replace( "-", " " ) )
											.asJson()
				projectResult = getResult( projectQuery )
				

			}
			
			def projectID = projectResult['id']
			// Add the analyst to the project (This may not be necessary)
			def addIPAQuery = Unirest.post( this.gitLabAPI+"/projects/$projectID/members" )
											.queryString("user_id", ipaUserID )
				
			
			// Set email on push for the //PUT /projects/:id/integrations/emails-on-push
			def emailOnPush = Unirest.put( this.gitLabAPI+"/projects/$projectID/integrations/emails-on-push" )
			.queryString( "recipients", userEmail )
			.asJson()	
			
			return [project:projectResult, analyst:ipaUserResult]
		}

		static def getGitLabSubGroup(def groupName ) {
			def groupQuery = Unirest.get( gitLabAPI+"/groups/${this.biopGroupId}/subgroups" )
			.queryString("search", groupName )
			.asJson()
			
			def result = getResult( groupQuery )
			
			if( result == null ) {
				// Create the group
				def newGroupQuery = Unirest.post( gitLabAPI+"/groups" )
											.queryString("path", groupName.toLowerCase() )
											.queryString("name", groupName )
											.queryString("parent_id", this.biopGroupId )
											.asJson()
			
				result = getResult( newGroupQuery )
			}
			
			return result['id']
		}
		
		// Helper function. Normally I would use getObject() but it does not work...
		static def getResult( def jsonRequest ) {
			def body = jsonRequest.getBody()
			IJ.log("BODY: $body")
			if ( body != null ) {
				def array = body.getArray()
				if(array.size() > 0) {
					return array[0]
				}
			}
			return null
		}
	}
	
	class Utilities {
		
		static String sanitize( String string ) {
			IJ.log( "Sanitizing String '$string' (replacing non alpanumeric characters with '-')" )
			def sanitized = string.replaceAll( /[^a-zA-Z0-9]/, '-' )

			// Remove extra dashes that occur contiguously
    		sanitized = sanitized.replaceAll( /-+/, '-' )
    		// Remove dashes at the beginning and end of the string
        	sanitized = sanitized.replaceAll( /^-+|-+$/, '' ).toLowerCase()
        	IJ.log( "    Final string: $sanitized" )
        	return sanitized
        
		}
		// Create a URL icon
		static File createInternetShortcut( String name, File parentFolder, String target ) {
			File file = new File( parentFolder, name +".url" )
		    if( !this.dryRun ) {
			    file.write "[InternetShortcut]\n"
			    file << "URL=" + target + "\n"
		    }
		}
		
		static File createProjectDataFolder( def gitLabProject ) {
			def groupFolder = new File( this.biopDataFolder, gitLabProject['namespace']['name'] )
			def projectFolder = new File( groupFolder, gitLabProject['path'] )
			if( !this.dryRun ) projectFolder.mkdirs()
			return projectFolder
		}
			    	
		
		static initialiseLocalGitandPush( def gitlabProject, def analyst ) {
			
			// Create local folder
			def gitGroupFolder = new File( this.localGitReposFolder, gitlabProject['namespace']['name'] )
			def localRepo = new File( gitGroupFolder, gitlabProject['path'] )
			localRepo.mkdirs()
			
			def readme = new File( localRepo, "readme.md")
	    	readme.write "# ${gitlabProject['name']}\n\n"
	    	readme << "Created by: ${analyst['name']}\n\n"
	    	readme << "Gitlab URL: ${gitlabProject['web_url']}\n"
			
			
			execute("cd $localRepo & cd ${gitlabProject['path']}" +
					"& git init --initial-branch=main" +
					"& git remote add origin ${gitlabProject['http_url_to_repo']}" +
					"& git add ." +
					"& git commit -m \"Initial commit\"" +
					"& git push --set-upstream origin main")
			
			return localRepo

			/* 
			 * cd existing_folder
			 * git init --initial-branch=main
			 * git remote add origin git@gitlab.epfl.ch:biop/nicolas.chiaruttini_ptbiop/the-greatest-project-ever.git
			 * git add .
			 * git commit -m "Initial commit"
			 * git push --set-upstream origin main 
			 */
		}
		
		static createShorcut( def shared_folder , def localRepo){
			def local_shortcut = new File( localRepo , "Shared_Folder.lnk")
			execute(" \"C:\\Program Files\\Git\\mingw64\\bin\\create-shortcut.exe\" $shared_folder $local_shortcut")
		}
		
		// LDAP Query to get the default group from a user
		static String getUserGroup( String email ) { 
			
			// Connect to EPFL LDAP
			def connection = new LdapNetworkConnection( 'ldap.epfl.ch' );
			
			// Authenticate (Bind) anonymously
			connection.bind()
			
			// Build a searchRequest that will look for the group name of the first group this email is associated with
			def  req = new SearchRequestImpl()
			
			// Search everywhere
			req.setScope( SearchScope.SUBTREE )
			// 'ou' is the group the person belongs to
			req.addAttributes( 'ou' )
			
			req.setTimeLimit( 0 )
			
			// We expect a single result anyway but let's limit it
			req.setSizeLimit( 5 )
			
			// This is the start location for the search: all of EPFL
			req.setBase( new Dn( 'o=epfl,c=ch' ) )
			
			// Here we are looking for a person AND an email that should exactly match AND the default group of the person
			req.setFilter( "(&(objectClass=person)(mail=${email})(EPFLAccredOrder=1))" )
			    
			// Finally run the search
			def results = connection.search( req )
			
			// Pick the results as a list, otherwise it is an iterator and we cannot ask its size without it being clijx.resetMetaData(null)
			// Probably a bug, but considering it is a small list, I am not worried too much about it
			def resList = results.toList()
			
			def group = 'UNKNOWN'
			
			//IJ.log( ""+resList)
			
			if( resList.size() == 1)
				group =  resList.get( 0 ).getEntry().get( 'ou' )[0]
			
			// Close the connection to the results
			results.close()
			
			// End the connection to LDAP
			connection.close()
			return group
		}
		
		private static execute( String command ) {
			def sout = new StringBuilder(), serr = new StringBuilder()
			def proc = ("cmd /c "+command).execute(System.getenv().collect{ it }, this.localGitReposFolder)
			proc.consumeProcessOutput(sout, serr)
			proc.waitForProcessOutput()
			IJ.log( "out> $sout\nerr> $serr")

		}
			
	}

}

import kong.unirest.Unirest

import org.apache.directory.ldap.client.api.*
import org.apache.directory.api.ldap.model.message.*
import org.apache.directory.api.ldap.model.name.Dn

import ij.IJ