from proofcore.core.defaultwrapper import DefaultWrapper

import math
class thermModel:
    def __init__(self):
        self.c = 0.477 #
        self.m = 10 # mass in g
        self.T_ambient = 293.15 # ambient temperature
        self.T = 0 #output of this model
        self.power_loss = 0.0 # input for this model
        super(thermModel, self).__init__()
    def init(self):
        return super(thermModel, self).init()
    def step(self):
        self.T_new(self.T)
        return super(thermModel, self).step()
    def finalize(self):
        return super(thermModel, self).finalize()

    def T_new(self,T): # Calcualte the resistance which is dependent on the temperature
        dT = self.power_loss * self.communication_step_size / (self.c*self.m)
        self.T += dT