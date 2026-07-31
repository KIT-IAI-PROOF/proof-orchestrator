from proofcore.core.defaultwrapper import DefaultWrapper
import math
import time
#import matplotlib.pyplot as plt
import logging
logger = logging.getLogger('thermModel')
logger.setLevel(logging.INFO)
ch = logging.StreamHandler()
ch.setLevel(logging.INFO)
formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
ch.setFormatter(formatter)
logger.addHandler(ch)
from proofcore.core.defaultwrapper import options
class elecModel(DefaultWrapper):
    def __init__(self):
        self.T_ambient = 293.15
        self.T0 = 273.15
        self.R0 = 100
        self.alpha = 3.85e-2
        self.U = 7.00
        self.Current = []
        self.power_loss = ""
        self.T = 0.0
        self.tmax = 0
        #ToDo: elexModel durch options ersetzen?!
        super(options, self).__init__()
        
    def init(self):
        self.R_T(self.T_ambient)
        print("Init Step: T set to ", self.T_ambient)
        return super(elecModel, self).init()

    def step(self):

        if self.communicationPoint+1 < self.tmax:
            logger.info("The temperature is at " + str(self.T)+ " K at communicationPoint" + str(self.communicationPoint))
            time.sleep(2)
            self.R_T(self.T)
            return super(elecModel, self).step()
        else:
            self.finalize()
    def finalize(self):
        #plt.plot(self.Current)
        #plt.savefig("mygraph.png")
        logger.info("Current:" + str(self.Current))
        return super(elecModel,self).finalize()


    def R_T(self,T): # Calcualte the resistance which is dependant on the temperature
        R = self.R0 * math.exp(self.alpha * (T - self.T0)) #resistance at the current temperature
        self.power_loss = str((self.U ** 2) / R)
        self.Current.append(self.U / R)